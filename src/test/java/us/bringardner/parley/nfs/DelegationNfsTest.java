/**
 * <PRE>
 *
 * Copyright Tony Bringardner 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 */
package us.bringardner.parley.nfs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.TestClient.Ops;
import us.bringardner.parley.nfs.TestClient.Result;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.XdrDecoder;
import us.bringardner.parley.nfs.rpc.XdrEncoder;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/**
 * Read delegations (decisions D44 to D47 and D56) over TCP: a {@link TestClient} is the NFS client, and a
 * {@link Callbacks} socket stands in for the callback server a real client runs.
 */
public class DelegationNfsTest {

	/** What a client's callback server does: answers CB_NULL and CB_COMPOUND, and keeps the recalls. */
	private static final class Callbacks implements AutoCloseable {
		final ServerSocket socket;
		final Thread thread;
		final List<Socket> open = Collections.synchronizedList(new ArrayList<Socket>());
		volatile int nulls;
		final List<byte[][]> recalls = Collections.synchronizedList(new ArrayList<byte[][]>());
		volatile boolean closed;

		/** @param host the address to listen on, null for all of them */
		Callbacks(String host) throws IOException {
			socket = new ServerSocket(0, 10, host == null ? null : InetAddress.getByName(host));
			thread = new Thread(this::run, "test-callbacks");
			thread.setDaemon(true);
			thread.start();
		}

		/** the universal address of this socket */
		String addr() {
			return addr(socket.getInetAddress().getHostAddress());
		}

		/** the universal address of this socket's port on a host */
		String addr(String host) {
			int p = socket.getLocalPort();
			return host+"."+(p >> 8)+"."+(p & 255);
		}

		private void run() {
			while( !closed ) {
				try {
					Socket s = socket.accept();
					open.add(s);
					Thread t = new Thread(() -> serve(s), "test-callbacks-conn");
					t.setDaemon(true);
					t.start();
				} catch (IOException e) {
					return;
				}
			}
		}

		private void serve(Socket s) {
			try (Socket c = s) {
				DataInputStream in = new DataInputStream(c.getInputStream());
				OutputStream out = c.getOutputStream();
				while( true ) {
					int header = in.readInt();
					byte[] record = new byte[header & 0x7fffffff];
					in.readFully(record);
					XdrDecoder d = new XdrDecoder(record);
					int xid = d.readInt();
					d.readInt();
					d.readInt();
					d.readUnsignedInt();
					d.readInt();
					int proc = d.readInt();
					d.readInt();
					d.readOpaque(400);
					d.readInt();
					d.readOpaque(400);
					XdrEncoder r = new XdrEncoder();
					r.writeInt(0).writeInt(xid).writeInt(1).writeInt(0).writeInt(0).writeOpaque(new byte[0]).writeInt(0);
					if( proc == 0 ) {
						nulls++;
					} else {
						d.readString(100);
						d.readInt();
						d.readInt();
						d.readInt();
						int op = d.readInt();
						assertEquals(Nfs4.OP_CB_RECALL, op);
						d.readInt();
						byte[] other = d.readFixedOpaque(12);
						d.readBoolean();
						byte[] fh = d.readOpaque(128);
						recalls.add(new byte[][] {other, fh});
						r.writeInt(0).writeString("").writeInt(1).writeInt(Nfs4.OP_CB_RECALL).writeInt(0);
					}
					r.setInt(0, 0x80000000 | (r.size()-4));
					out.write(r.array(), 0, r.size());
					out.flush();
				}
			} catch (IOException | RuntimeException e) {
				// the connection ended
			} catch (Exception e) {
				// a bad call
			}
		}

		@Override
		public void close() throws IOException {
			closed = true;
			socket.close();
			synchronized (open) {
				for (Socket s : open) {
					s.close();
				}
			}
			try {
				thread.join(5000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private MemoryFileSourceFactory mem;
	private FileSource root;
	private NfsServer server;
	private final List<TestClient> clients = new ArrayList<TestClient>();
	private final List<Callbacks> callbacks = new ArrayList<Callbacks>();
	private final List<String> properties = new ArrayList<String>();
	private final Map<String, int[]> seqids = new HashMap<String, int[]>();

	private void property(String name, String value) {
		System.setProperty(name, value);
		properties.add(name);
	}

	private void start(boolean delegations, String lease) throws Exception {
		if( delegations ) {
			property("test.deleg.delegations", "true");
		}
		property("test.deleg.leaseTime", lease);
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		FileSource f = root.getChild("f");
		assertTrue(f.createNewFile());
		server = new NfsServer("test.deleg", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
	}

	@AfterEach
	public void tearDown() throws Exception {
		for (TestClient c : clients) {
			c.close();
		}
		if( server != null ) {
			server.stop();
			assertTrue(server.awaitStopped(10000));
		}
		for (Callbacks c : callbacks) {
			c.close();
		}
		if( mem != null ) {
			mem.disConnect();
		}
		for (String p : properties) {
			System.clearProperty(p);
		}
	}

	private TestClient client() throws IOException {
		TestClient c = new TestClient(server.getLocalPort());
		clients.add(c);
		return c;
	}

	private Callbacks callbacks(String host) throws IOException {
		Callbacks c = new Callbacks(host);
		callbacks.add(c);
		return c;
	}

	/** @return an IPv4 address of this machine that isn't a loopback address, null for none */
	private static String otherAddress() throws IOException {
		for (java.net.NetworkInterface i : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
			if( i.isUp() && !i.isLoopback() ) {
				for (InetAddress a : Collections.list(i.getInetAddresses())) {
					if( a instanceof java.net.Inet4Address && !a.isLinkLocalAddress() ) {
						return a.getHostAddress();
					}
				}
			}
		}
		return null;
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	/** SETCLIENTID and its confirm, with this callback address ("" for none). */
	private static long clientid(TestClient c, String name, String cbAddr) throws IOException {
		Ops ops = new Ops().op(Nfs4.OP_SETCLIENTID);
		ops.args().writeFixedOpaque(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
		ops.args().writeOpaque(bytes(name));
		ops.args().writeInt(0x40000000).writeString("tcp").writeString(cbAddr).writeInt(7);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_SETCLIENTID);
		long id = r.in.readLong();
		byte[] verf = r.in.readFixedOpaque(8);
		Ops conf = new Ops().op(Nfs4.OP_SETCLIENTID_CONFIRM);
		conf.args().writeLong(id).writeFixedOpaque(verf);
		assertEquals(Nfs4.NFS4_OK, c.compound(conf).status);
		return id;
	}

	private static byte[] rootFh(TestClient c) throws IOException {
		Result r = c.compound(new Ops().putroot().getfh());
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_GETFH);
		return r.in.readOpaque(128);
	}

	private static final class Opened {
		int status;
		int seqid;
		byte[] other;
		byte[] fh;
		int delegation = -1;
		byte[] delegationOther;
	}

	/** OPEN of a file in the folder, by an open-owner of this name (its first OPEN is confirmed). */
	private Opened open(TestClient c, byte[] dir, long clientid, int access, String owner, String name) throws IOException {
		int[] seq = seqids.computeIfAbsent(owner, k -> new int[] {1});
		Ops ops = new Ops().putfh(dir).op(Nfs4.OP_OPEN);
		ops.args().writeInt(seq[0]++).writeInt(access).writeInt(0).writeLong(clientid).writeOpaque(bytes(owner))
			.writeInt(Nfs4.OPEN4_NOCREATE).writeInt(Nfs4.CLAIM_NULL).writeString(name);
		ops.getfh();
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		Opened o = new Opened();
		o.status = r.next(Nfs4.OP_OPEN);
		if( o.status != Nfs4.NFS4_OK ) {
			return o;
		}
		o.seqid = r.in.readInt();
		o.other = r.in.readFixedOpaque(12);
		r.in.readBoolean();
		r.in.readLong();
		r.in.readLong();
		int rflags = r.in.readInt();
		r.in.readIntArray(8);
		o.delegation = r.in.readInt();
		if( o.delegation == Nfs4.OPEN_DELEGATE_READ ) {
			r.in.readInt();
			o.delegationOther = r.in.readFixedOpaque(12);
			assertFalse(r.in.readBoolean());
			r.in.readInt();
			r.in.readInt();
			r.in.readInt();
			assertEquals("EVERYONE@", r.in.readString(100));
		}
		r.ok(Nfs4.OP_GETFH);
		o.fh = r.in.readOpaque(128);
		if( (rflags & Nfs4.OPEN4_RESULT_CONFIRM) != 0 ) {
			Ops conf = new Ops().putfh(o.fh).op(Nfs4.OP_OPEN_CONFIRM);
			conf.args().writeInt(o.seqid).writeFixedOpaque(o.other).writeInt(seq[0]++);
			Result cr = c.compound(conf);
			cr.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_OPEN_CONFIRM);
			o.seqid = cr.in.readInt();
			o.other = cr.in.readFixedOpaque(12);
		}
		return o;
	}

	private static int delegreturn(TestClient c, byte[] fh, byte[] other) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_DELEGRETURN);
		ops.args().writeInt(1).writeFixedOpaque(other);
		return c.compound(ops).status;
	}

	private static int read(TestClient c, byte[] fh, byte[] other) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_READ);
		ops.args().writeInt(1).writeFixedOpaque(other).writeLong(0).writeInt(100);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_READ);
	}

	private static int write(TestClient c, byte[] fh, byte[] other) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_WRITE);
		ops.args().writeInt(1).writeFixedOpaque(other).writeLong(0).writeInt(Nfs4.FILE_SYNC4).writeOpaque(bytes("x"));
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_WRITE);
	}

	private static int remove(TestClient c, byte[] dir, String name) throws IOException {
		Ops ops = new Ops().putfh(dir).op(Nfs4.OP_REMOVE);
		ops.args().writeString(name);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_REMOVE);
	}

	private static void waitFor(java.util.function.BooleanSupplier done, String what) throws InterruptedException {
		long end = System.currentTimeMillis()+5000;
		while( !done.getAsBoolean() ) {
			assertTrue(System.currentTimeMillis() < end, "timed out waiting for "+what);
			Thread.sleep(20);
		}
	}

	/** A client with a working callback address that holds a read delegation of f. */
	private Opened holder(TestClient c, Callbacks cb, String name) throws Exception {
		long id = clientid(c, name, cb.addr());
		waitFor(() -> cb.nulls > 0, "the callback check");
		Thread.sleep(200);
		byte[] dir = rootFh(c);
		assertEquals(Nfs4.OPEN_DELEGATE_NONE, open(c, dir, id, Nfs4.OPEN4_SHARE_ACCESS_READ, name+"-owner", "f").delegation,
				"the open-owner's first open is confirmed, no delegation yet");
		Opened o = open(c, dir, id, Nfs4.OPEN4_SHARE_ACCESS_READ, name+"-owner", "f");
		assertEquals(Nfs4.NFS4_OK, o.status);
		return o;
	}

	// ------------------------------------------------------------------ tests

	/** Decision D47: nothing is given, and nothing is called, unless the setting is on. */
	@Test
	public void offUnlessTheSettingIsOn() throws Exception {
		start(false, "90s");
		Callbacks cb = callbacks("127.0.0.1");
		TestClient c = client();
		long id = clientid(c, "a", cb.addr());
		Thread.sleep(300);
		assertEquals(0, cb.nulls, "no callback check");
		byte[] dir = rootFh(c);
		open(c, dir, id, Nfs4.OPEN4_SHARE_ACCESS_READ, "o", "f");
		assertEquals(Nfs4.OPEN_DELEGATE_NONE, open(c, dir, id, Nfs4.OPEN4_SHARE_ACCESS_READ, "o", "f").delegation);
		assertNull(server.getDelegations());
	}

	/** Decisions D44 and D46: a read delegation, read with its stateid, recalled when another client writes. */
	@Test
	public void aWriterRecallsTheDelegation() throws Exception {
		start(true, "90s");
		Callbacks cbA = callbacks("127.0.0.1");
		TestClient a = client();
		Opened held = holder(a, cbA, "a");
		assertEquals(Nfs4.OPEN_DELEGATE_READ, held.delegation);
		assertEquals(Nfs4.NFS4_OK, read(a, held.fh, held.delegationOther), "READ with the delegation's stateid");
		assertEquals(Nfs4.NFS4ERR_OPENMODE, write(a, held.fh, held.delegationOther), "it doesn't write");
		assertEquals(1, server.getStateManager().getDelegationCount());

		TestClient b = client();
		long bid = clientid(b, "b", "");
		byte[] dir = rootFh(b);
		assertEquals(Nfs4.NFS4ERR_DELAY, open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f").status,
				"the writer waits for the recall");
		waitFor(() -> cbA.recalls.size() == 1, "the CB_RECALL");
		assertArrayEquals(held.delegationOther, cbA.recalls.get(0)[0], "the delegation's stateid");
		assertArrayEquals(held.fh, cbA.recalls.get(0)[1], "the file's handle");
		assertEquals(Nfs4.NFS4ERR_DELAY, open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f").status,
				"still waiting; one recall is sent");
		Thread.sleep(100);
		assertEquals(1, cbA.recalls.size());

		assertEquals(Nfs4.NFS4_OK, delegreturn(a, held.fh, held.delegationOther));
		assertEquals(0, server.getStateManager().getDelegationCount());
		assertEquals(Nfs4.NFS4ERR_BAD_STATEID, delegreturn(a, held.fh, held.delegationOther), "already returned");
		Opened w = open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f");
		assertEquals(Nfs4.NFS4_OK, w.status, "the writer goes on once it is back");
	}

	/** Delegations are shared: another reader neither waits nor recalls. */
	@Test
	public void readersShareDelegations() throws Exception {
		start(true, "90s");
		Callbacks cbA = callbacks("127.0.0.1");
		Callbacks cbB = callbacks("127.0.0.1");
		Opened a = holder(client(), cbA, "a");
		Opened b = holder(client(), cbB, "b");
		assertEquals(Nfs4.OPEN_DELEGATE_READ, a.delegation);
		assertEquals(Nfs4.OPEN_DELEGATE_READ, b.delegation);
		assertEquals(2, server.getStateManager().getDelegationCount());
		assertTrue(cbA.recalls.isEmpty() && cbB.recalls.isEmpty());
	}

	/** A client without a callback that answers is given none (RFC 7530 section 10.4). */
	@Test
	public void noCallbackNoDelegation() throws Exception {
		start(true, "90s");
		Callbacks cb = callbacks("127.0.0.1");
		String dead = cb.addr();
		cb.close();
		TestClient c = client();
		long id = clientid(c, "a", dead);
		Thread.sleep(500);
		byte[] dir = rootFh(c);
		open(c, dir, id, Nfs4.OPEN4_SHARE_ACCESS_READ, "o", "f");
		assertEquals(Nfs4.OPEN_DELEGATE_NONE, open(c, dir, id, Nfs4.OPEN4_SHARE_ACCESS_READ, "o", "f").delegation);
		TestClient d = client();
		long did = clientid(d, "d", "");
		open(d, dir, did, Nfs4.OPEN4_SHARE_ACCESS_READ, "o2", "f");
		assertEquals(Nfs4.OPEN_DELEGATE_NONE, open(d, dir, did, Nfs4.OPEN4_SHARE_ACCESS_READ, "o2", "f").delegation,
				"no callback address at all");
	}

	/** Decision D56: only the host the client came from may be called. */
	@Test
	public void theCallbackAddressIsTheClientsOwnHost() throws Exception {
		start(true, "90s");
		// a callback server that answers on this machine's other address, which isn't where the client comes from
		String other = otherAddress();
		org.junit.jupiter.api.Assumptions.assumeTrue(other != null, "this machine has no address besides 127.0.0.1");
		Callbacks elsewhere = callbacks(null);
		TestClient c = client();
		long id = clientid(c, "a", elsewhere.addr(other));
		Thread.sleep(500);
		assertEquals(0, elsewhere.nulls, "another host isn't called");
		byte[] dir = rootFh(c);
		open(c, dir, id, Nfs4.OPEN4_SHARE_ACCESS_READ, "o", "f");
		assertEquals(Nfs4.OPEN_DELEGATE_NONE, open(c, dir, id, Nfs4.OPEN4_SHARE_ACCESS_READ, "o", "f").delegation);
	}

	/** A delegation not returned within the lease is taken back, and the writer goes on. */
	@Test
	public void aDelegationNotReturnedIsRevoked() throws Exception {
		start(true, "2s");
		Callbacks cbA = callbacks("127.0.0.1");
		TestClient a = client();
		Opened held = holder(a, cbA, "a");
		assertEquals(Nfs4.OPEN_DELEGATE_READ, held.delegation);
		TestClient b = client();
		long bid = clientid(b, "b", "");
		byte[] dir = rootFh(b);
		assertEquals(Nfs4.NFS4ERR_DELAY, open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f").status);
		long end = System.currentTimeMillis()+10000;
		int status;
		do {
			Thread.sleep(300);
			status = open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f").status;
		} while( status == Nfs4.NFS4ERR_DELAY && System.currentTimeMillis() < end );
		assertEquals(Nfs4.NFS4_OK, status, "the delegation was revoked after the lease");
		assertEquals(Nfs4.NFS4ERR_DELEG_REVOKED, read(a, held.fh, held.delegationOther));
		assertEquals(0, server.getStateManager().getDelegationCount());
	}

	/** A file that others have delegated isn't removed until they return it. */
	@Test
	public void removeWaitsForTheDelegation() throws Exception {
		start(true, "90s");
		Callbacks cbA = callbacks("127.0.0.1");
		TestClient a = client();
		Opened held = holder(a, cbA, "a");
		TestClient b = client();
		clientid(b, "b", "");
		byte[] dir = rootFh(b);
		assertEquals(Nfs4.NFS4ERR_DELAY, remove(b, dir, "f"));
		waitFor(() -> cbA.recalls.size() == 1, "the CB_RECALL");
		assertTrue(root.getChild("f").exists());
		assertEquals(Nfs4.NFS4_OK, delegreturn(a, held.fh, held.delegationOther));
		assertEquals(Nfs4.NFS4_OK, remove(b, dir, "f"));
		assertFalse(root.getChild("f").exists());
		assertNotNull(held);
	}
}
