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
import java.util.Comparator;
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
					int flavor = d.readInt();
					d.readOpaque(400);
					d.readInt();
					d.readOpaque(400);
					XdrEncoder r = new XdrEncoder();
					if( proc != 0 && flavor == 0 ) {
						// like a Linux client: a callback with no credential is denied (AUTH_TOOWEAK)
						r.writeInt(0).writeInt(xid).writeInt(1).writeInt(1).writeInt(1).writeInt(5);
						r.setInt(0, 0x80000000 | (r.size()-4));
						out.write(r.array(), 0, r.size());
						out.flush();
						continue;
					}
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
		start(delegations, false, lease);
	}

	private java.nio.file.Path stateDir;

	/** Start with a state folder, so that delegations and file handles survive {@link #restart()}. */
	private void startKeeping(String lease) throws Exception {
		stateDir = java.nio.file.Files.createTempDirectory("nfs-deleg-state");
		property("test.deleg.stateDir", stateDir.toString());
		start(true, true, lease);
	}

	/** Stop the server and start it again on the same files and state folder: a restart. */
	private void restart() throws Exception {
		for (TestClient c : clients) {
			c.close();
		}
		clients.clear();
		server.stop();
		assertTrue(server.awaitStopped(10000));
		server = new NfsServer("test.deleg", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
	}

	private void start(boolean delegations, boolean writes, String lease) throws Exception {
		if( delegations ) {
			property("test.deleg.delegations", "true");
		}
		if( writes ) {
			property("test.deleg.writeDelegations", "true");
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
		if( stateDir != null ) {
			try (java.util.stream.Stream<java.nio.file.Path> w = java.nio.file.Files.walk(stateDir)) {
				w.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
			}
		}
	}

	private TestClient client() throws IOException {
		TestClient c = new TestClient(server.getLocalPort());
		clients.add(c);
		return c;
	}

	/** a client that comes from the given address of this machine */
	private TestClient clientFrom(String host) throws IOException {
		TestClient c = new TestClient(host, server.getLocalPort());
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
		return clientid(c, name, cbAddr, (byte) 1);
	}

	/** SETCLIENTID with a verifier that starts with this byte: another one is a client that rebooted. */
	private static long clientid(TestClient c, String name, String cbAddr, byte verifier) throws IOException {
		Ops ops = new Ops().op(Nfs4.OP_SETCLIENTID);
		ops.args().writeFixedOpaque(new byte[] {verifier, 2, 3, 4, 5, 6, 7, 8});
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
		} else if( o.delegation == Nfs4.OPEN_DELEGATE_WRITE ) {
			r.in.readInt();
			o.delegationOther = r.in.readFixedOpaque(12);
			assertFalse(r.in.readBoolean());
			assertEquals(Nfs4.NFS_LIMIT_SIZE, r.in.readInt());
			assertEquals(0L, r.in.readLong(), "a size limit of 0 (D64)");
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


	/** OPEN with CLAIM_PREVIOUS of the file the handle names, saying the client held this delegation (decision D67). */
	private Opened reclaim(TestClient c, byte[] fh, long clientid, int access, String owner, int held) throws IOException {
		int[] seq = seqids.computeIfAbsent(owner, k -> new int[] {1});
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_OPEN);
		ops.args().writeInt(seq[0]++).writeInt(access).writeInt(0).writeLong(clientid).writeOpaque(bytes(owner))
			.writeInt(Nfs4.OPEN4_NOCREATE).writeInt(Nfs4.CLAIM_PREVIOUS).writeInt(held);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		Opened o = new Opened();
		o.fh = fh;
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
		if( o.delegation != Nfs4.OPEN_DELEGATE_NONE ) {
			r.in.readInt();
			o.delegationOther = r.in.readFixedOpaque(12);
			assertFalse(r.in.readBoolean());
			if( o.delegation == Nfs4.OPEN_DELEGATE_WRITE ) {
				assertEquals(Nfs4.NFS_LIMIT_SIZE, r.in.readInt());
				r.in.readLong();
			}
			r.in.readInt();
			r.in.readInt();
			r.in.readInt();
			assertEquals("EVERYONE@", r.in.readString(100));
		}
		assertEquals(0, rflags & Nfs4.OPEN4_RESULT_CONFIRM, "a reclaim is never asked to confirm (RFC 7530 section 16.16.5)");
		if( (rflags & Nfs4.OPEN4_RESULT_CONFIRM) != 0 ) {
			Ops conf = new Ops().putfh(fh).op(Nfs4.OP_OPEN_CONFIRM);
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

	private static int getattr(TestClient c, byte[] fh, int... bits) throws IOException {
		Result r = c.compound(new Ops().putfh(fh).getattr(bits));
		r.ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_GETATTR);
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

	/** A client with a working callback address that holds a write delegation of f. */
	private Opened writeHolder(TestClient c, Callbacks cb, String name) throws Exception {
		long id = clientid(c, name, cb.addr());
		waitFor(() -> cb.nulls > 0, "the callback check");
		Thread.sleep(200);
		byte[] dir = rootFh(c);
		assertEquals(Nfs4.OPEN_DELEGATE_NONE, open(c, dir, id, Nfs4.OPEN4_SHARE_ACCESS_WRITE, name+"-owner", "f").delegation,
				"the open-owner's first open is confirmed, no delegation yet");
		return open(c, dir, id, Nfs4.OPEN4_SHARE_ACCESS_WRITE, name+"-owner", "f");
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

	// ------------------------------------------------------------------ write delegations (D62 to D65)

	/** Decision D62: nothing is given for writing unless its own setting is on. */
	@Test
	public void writeDelegationsAreOffUnlessTheirSettingIsOn() throws Exception {
		start(true, "90s");
		Callbacks cb = callbacks("127.0.0.1");
		Opened o = writeHolder(client(), cb, "a");
		assertEquals(Nfs4.NFS4_OK, o.status);
		assertEquals(Nfs4.OPEN_DELEGATE_NONE, o.delegation);
		assertFalse(server.getWriteDelegations());
	}

	/** A write open of a file nobody else has is given a write delegation, which reads and writes. */
	@Test
	public void aWriteOpenGetsAWriteDelegation() throws Exception {
		start(true, true, "90s");
		assertTrue(server.getWriteDelegations());
		Callbacks cb = callbacks("127.0.0.1");
		TestClient a = client();
		Opened held = writeHolder(a, cb, "a");
		assertEquals(Nfs4.OPEN_DELEGATE_WRITE, held.delegation);
		assertEquals(Nfs4.NFS4_OK, write(a, held.fh, held.delegationOther), "WRITE with the delegation's stateid");
		assertEquals(Nfs4.NFS4_OK, read(a, held.fh, held.delegationOther), "READ with it");
		assertEquals(1, server.getStateManager().getDelegationCount());
		assertTrue(root.getChild("f").length() > 0, "what the holder wrote is in the file");
	}

	/** Decision D63: another client's read open waits for the holder to write its changes back. */
	@Test
	public void anotherClientsReadRecallsIt() throws Exception {
		start(true, true, "90s");
		Callbacks cb = callbacks("127.0.0.1");
		TestClient a = client();
		Opened held = writeHolder(a, cb, "a");
		assertEquals(Nfs4.OPEN_DELEGATE_WRITE, held.delegation);
		TestClient b = client();
		long bid = clientid(b, "b", "");
		byte[] dir = rootFh(b);
		assertEquals(Nfs4.NFS4ERR_DELAY, open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_READ, "bo", "f").status);
		waitFor(() -> cb.recalls.size() == 1, "the CB_RECALL");
		assertArrayEquals(held.delegationOther, cb.recalls.get(0)[0]);
		assertEquals(Nfs4.NFS4_OK, delegreturn(a, held.fh, held.delegationOther));
		assertEquals(0, server.getStateManager().getDelegationCount());
		assertEquals(Nfs4.NFS4_OK, open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_READ, "bo", "f").status);
	}

	/** Another client's write open waits for it too. */
	@Test
	public void anotherClientsWriteRecallsIt() throws Exception {
		start(true, true, "90s");
		Callbacks cb = callbacks("127.0.0.1");
		TestClient a = client();
		Opened held = writeHolder(a, cb, "a");
		TestClient b = client();
		long bid = clientid(b, "b", "");
		byte[] dir = rootFh(b);
		assertEquals(Nfs4.NFS4ERR_DELAY, open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f").status);
		waitFor(() -> cb.recalls.size() == 1, "the CB_RECALL");
		assertEquals(Nfs4.NFS4_OK, delegreturn(a, held.fh, held.delegationOther));
		assertEquals(Nfs4.NFS4_OK, open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f").status);
	}

	/** The holder's own opens are no conflict, and neither are its own requests for the size. */
	@Test
	public void theHolderIsNoConflict() throws Exception {
		start(true, true, "90s");
		Callbacks cb = callbacks("127.0.0.1");
		TestClient a = client();
		Opened held = writeHolder(a, cb, "a");
		long id = clientid(a, "a", cb.addr());
		byte[] dir = rootFh(a);
		assertEquals(Nfs4.NFS4_OK, open(a, dir, id, Nfs4.OPEN4_SHARE_ACCESS_BOTH, "a-owner", "f").status, "its own open");
		assertEquals(Nfs4.NFS4_OK, getattr(a, held.fh, Nfs4.FATTR4_SIZE, Nfs4.FATTR4_CHANGE, Nfs4.FATTR4_TIME_MODIFY),
				"its own request, from its own host (D65)");
		Thread.sleep(100);
		assertTrue(cb.recalls.isEmpty());
		assertEquals(1, server.getStateManager().getDelegationCount());
	}

	/** Decisions D63 and D65: a request for the size from another host recalls it; one for the type doesn't. */
	@Test
	public void anotherHostsRequestForTheSizeRecallsIt() throws Exception {
		start(true, true, "90s");
		String other = otherAddress();
		org.junit.jupiter.api.Assumptions.assumeTrue(other != null, "this machine has no address besides 127.0.0.1");
		Callbacks cb = callbacks("127.0.0.1");
		TestClient a = client();
		Opened held = writeHolder(a, cb, "a");
		assertEquals(Nfs4.OPEN_DELEGATE_WRITE, held.delegation);
		TestClient b = clientFrom(other);
		assertEquals(Nfs4.NFS4_OK, getattr(b, held.fh, Nfs4.FATTR4_TYPE), "the type doesn't change by writing");
		Thread.sleep(100);
		assertTrue(cb.recalls.isEmpty());
		assertEquals(Nfs4.NFS4ERR_DELAY, getattr(b, held.fh, Nfs4.FATTR4_SIZE), "the size does");
		waitFor(() -> cb.recalls.size() == 1, "the CB_RECALL");
		assertEquals(Nfs4.NFS4_OK, delegreturn(a, held.fh, held.delegationOther));
		assertEquals(Nfs4.NFS4_OK, getattr(b, held.fh, Nfs4.FATTR4_SIZE));
	}

	/** A write delegation is given only when no other client has the file open. */
	@Test
	public void noWriteDelegationWhileAnotherClientHasTheFileOpen() throws Exception {
		start(true, true, "90s");
		TestClient b = client();
		long bid = clientid(b, "b", "");
		byte[] dir = rootFh(b);
		open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_READ, "bo", "f");
		assertEquals(Nfs4.NFS4_OK, open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_READ, "bo", "f").status);
		Callbacks cb = callbacks("127.0.0.1");
		Opened o = writeHolder(client(), cb, "a");
		assertEquals(Nfs4.NFS4_OK, o.status);
		assertEquals(Nfs4.OPEN_DELEGATE_NONE, o.delegation, "b has it open");
		assertEquals(0, server.getStateManager().getDelegationCount());
	}

	/** A write delegation not returned within the lease is taken back, and its stateid is revoked. */
	@Test
	public void aWriteDelegationNotReturnedIsRevoked() throws Exception {
		start(true, true, "2s");
		Callbacks cb = callbacks("127.0.0.1");
		TestClient a = client();
		Opened held = writeHolder(a, cb, "a");
		assertEquals(Nfs4.OPEN_DELEGATE_WRITE, held.delegation);
		TestClient b = client();
		long bid = clientid(b, "b", "");
		byte[] dir = rootFh(b);
		assertEquals(Nfs4.NFS4ERR_DELAY, open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_READ, "bo", "f").status);
		long end = System.currentTimeMillis()+10000;
		int status;
		do {
			Thread.sleep(300);
			status = open(b, dir, bid, Nfs4.OPEN4_SHARE_ACCESS_READ, "bo", "f").status;
		} while( status == Nfs4.NFS4ERR_DELAY && System.currentTimeMillis() < end );
		assertEquals(Nfs4.NFS4_OK, status, "the reader goes on once it is revoked");
		assertEquals(0, server.getStateManager().getDelegationCount());
		assertEquals(Nfs4.NFS4ERR_DELEG_REVOKED, write(a, held.fh, held.delegationOther), "the holder is told");
	}

	// ------------------------------------------------------------------ after a restart (D67)

	/** A client takes back its write delegation after a restart; until it does, the file is closed to others. */
	@Test
	public void aDelegationIsReclaimedAfterARestart() throws Exception {
		startKeeping("90s");
		Callbacks cb = callbacks("127.0.0.1");
		Opened held = writeHolder(client(), cb, "a");
		assertEquals(Nfs4.OPEN_DELEGATE_WRITE, held.delegation);
		restart();
		assertTrue(server.getStateManager().isReclaiming());
		TestClient b = client();
		long bid = clientid(b, "b", "");
		assertEquals(Nfs4.NFS4ERR_GRACE, open(b, rootFh(b), bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f").status,
				"the holder may still come back");
		assertEquals(Nfs4.NFS4ERR_GRACE, open(b, rootFh(b), bid, Nfs4.OPEN4_SHARE_ACCESS_READ, "bo2", "f").status,
				"a read open sees what it holds back");
		TestClient a = client();
		long id = clientid(a, "a", cb.addr());
		Opened back = reclaim(a, held.fh, id, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "a-owner", Nfs4.OPEN_DELEGATE_WRITE);
		assertEquals(Nfs4.NFS4_OK, back.status);
		assertEquals(Nfs4.OPEN_DELEGATE_WRITE, back.delegation);
		assertEquals(Nfs4.NFS4_OK, write(a, held.fh, back.delegationOther), "it writes with the delegation again");
		assertEquals(1, server.getStateManager().getDelegationCount());
		assertEquals(Nfs4.NFS4ERR_DELAY, open(b, rootFh(b), bid, Nfs4.OPEN4_SHARE_ACCESS_READ, "bo2", "f").status,
				"now it is an ordinary write delegation: recalled");
		waitFor(() -> cb.recalls.size() == 1, "the CB_RECALL");
		assertEquals(Nfs4.NFS4_OK, delegreturn(a, held.fh, back.delegationOther));
		assertEquals(Nfs4.NFS4_OK, open(b, rootFh(b), bid, Nfs4.OPEN4_SHARE_ACCESS_READ, "bo2", "f").status);
	}

	/** A read delegation can be reclaimed too, and a second open of the file under it too. */
	@Test
	public void aReadDelegationIsReclaimedAfterARestart() throws Exception {
		startKeeping("90s");
		Callbacks cb = callbacks("127.0.0.1");
		Opened held = holder(client(), cb, "a");
		assertEquals(Nfs4.OPEN_DELEGATE_READ, held.delegation);
		restart();
		TestClient b = client();
		long bid = clientid(b, "b", "");
		assertEquals(Nfs4.NFS4_OK, open(b, rootFh(b), bid, Nfs4.OPEN4_SHARE_ACCESS_READ, "bo", "f").status,
				"another client's read doesn't conflict with a read delegation");
		assertEquals(Nfs4.NFS4ERR_GRACE, open(b, rootFh(b), bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f").status,
				"its write does");
		TestClient a = client();
		long id = clientid(a, "a", cb.addr());
		Opened one = reclaim(a, held.fh, id, Nfs4.OPEN4_SHARE_ACCESS_READ, "a-owner", Nfs4.OPEN_DELEGATE_READ);
		assertEquals(Nfs4.OPEN_DELEGATE_READ, one.delegation);
		Opened two = reclaim(a, held.fh, id, Nfs4.OPEN4_SHARE_ACCESS_READ, "a-owner2", Nfs4.OPEN_DELEGATE_READ);
		assertEquals(Nfs4.NFS4_OK, two.status);
		assertEquals(Nfs4.OPEN_DELEGATE_NONE, two.delegation, "the client has it back already");
		assertEquals(1, server.getStateManager().getDelegationCount());
	}

	/** What nobody came back for is given up when the lease is over, and the file is open again. */
	@Test
	public void anUnreclaimedDelegationIsGivenUpAfterTheLease() throws Exception {
		startKeeping("2s");
		Callbacks cb = callbacks("127.0.0.1");
		Opened held = writeHolder(client(), cb, "a");
		restart();
		List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<String>();
		server.getStateManager().setWarnings(warnings::add);
		TestClient b = client();
		long bid = clientid(b, "b", "");
		assertEquals(Nfs4.NFS4ERR_GRACE, open(b, rootFh(b), bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f").status);
		long end = System.currentTimeMillis()+10000;
		int status;
		do {
			Thread.sleep(200);
			status = open(b, rootFh(b), bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f").status;
		} while( status == Nfs4.NFS4ERR_GRACE && System.currentTimeMillis() < end );
		assertEquals(Nfs4.NFS4_OK, status);
		assertFalse(server.getStateManager().isReclaiming());
		assertEquals(1, warnings.size(), warnings.toString());
		assertTrue(warnings.get(0).contains("test:/f"), "the warning names the file by its path: "+warnings.get(0));
		TestClient a = client();
		long id = clientid(a, "a", cb.addr());
		assertEquals(Nfs4.NFS4ERR_NO_GRACE, reclaim(a, held.fh, id, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "a-owner", Nfs4.OPEN_DELEGATE_WRITE).status);
	}

	/** A client that starts again (another verifier) lost what it held: nothing waits for it. */
	@Test
	public void aRebootedClientDoesNotReclaim() throws Exception {
		startKeeping("90s");
		Callbacks cb = callbacks("127.0.0.1");
		Opened held = writeHolder(client(), cb, "a");
		restart();
		TestClient a = client();
		long id = clientid(a, "a", cb.addr(), (byte) 9);
		TestClient b = client();
		long bid = clientid(b, "b", "");
		assertEquals(Nfs4.NFS4_OK, open(b, rootFh(b), bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f").status);
		assertEquals(Nfs4.NFS4ERR_NO_GRACE, reclaim(a, held.fh, id, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "a-owner", Nfs4.OPEN_DELEGATE_WRITE).status);
	}

	/** Only the client that held a delegation, as the kind it held, can reclaim it; opens without one aren't kept (D7). */
	@Test
	public void onlyTheHolderReclaimsWhatItHeld() throws Exception {
		startKeeping("90s");
		Callbacks cb = callbacks("127.0.0.1");
		Opened held = writeHolder(client(), cb, "a");
		restart();
		TestClient c = client();
		long cid = clientid(c, "c", "");
		assertEquals(Nfs4.NFS4ERR_NO_GRACE, reclaim(c, held.fh, cid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "c-owner", Nfs4.OPEN_DELEGATE_WRITE).status,
				"another client");
		TestClient a = client();
		long id = clientid(a, "a", cb.addr());
		assertEquals(Nfs4.NFS4ERR_NO_GRACE, reclaim(a, held.fh, id, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "a-owner", Nfs4.OPEN_DELEGATE_NONE).status,
				"an open that had no delegation");
		assertEquals(Nfs4.NFS4ERR_RECLAIM_BAD, reclaim(a, held.fh, id, Nfs4.OPEN4_SHARE_ACCESS_READ, "a-owner", Nfs4.OPEN_DELEGATE_READ).status,
				"it held a write delegation");
		assertEquals(Nfs4.NFS4_OK, reclaim(a, held.fh, id, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "a-owner", Nfs4.OPEN_DELEGATE_WRITE).status);
	}

	/** A delegation that was returned isn't waited for after a restart, and with none there is no grace period. */
	@Test
	public void aReturnedDelegationIsNotWaitedFor() throws Exception {
		startKeeping("90s");
		Callbacks cb = callbacks("127.0.0.1");
		TestClient a = client();
		Opened held = writeHolder(a, cb, "a");
		assertEquals(Nfs4.NFS4_OK, delegreturn(a, held.fh, held.delegationOther));
		restart();
		assertFalse(server.getStateManager().isReclaiming());
		TestClient b = client();
		long bid = clientid(b, "b", "");
		assertEquals(Nfs4.NFS4_OK, open(b, rootFh(b), bid, Nfs4.OPEN4_SHARE_ACCESS_WRITE, "bo", "f").status);
	}
}
