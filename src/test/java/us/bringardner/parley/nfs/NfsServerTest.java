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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.permission.BasicParleyPermission;
import us.bringardner.parley.iam.permission.StandardPermissions;
import us.bringardner.parley.iam.registry.BasicUserRegistry;
import us.bringardner.parley.nfs.TestClient.Ops;
import us.bringardner.parley.nfs.TestClient.Result;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.RecordMarkingDecoder;
import us.bringardner.parley.nfs.rpc.Rpc;
import us.bringardner.parley.nfs.rpc.XdrDecoder;
import us.bringardner.parley.nfs.rpc.XdrEncoder;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/**
 * The server over TCP with {@link TestClient}, sharing a memory file system.
 */
public class NfsServerTest {

	private MemoryFileSourceFactory mem;
	private FileSource root;
	private NfsServer server;
	private final List<TestClient> clients = new ArrayList<TestClient>();
	private final List<String> properties = new ArrayList<String>();

	private TestClient start(String prefix, boolean readOnly) throws IOException {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer(prefix, 0);
		server.setExport(new Export("test", root, readOnly));
		server.startAndWait(10000);
		return client();
	}

	private TestClient client() throws IOException {
		TestClient c = new TestClient(server.getLocalPort());
		clients.add(c);
		return c;
	}

	private void property(String name, String value) {
		System.setProperty(name, value);
		properties.add(name);
	}

	@AfterEach
	public void tearDown() throws Exception {
		for (TestClient c : clients) {
			c.close();
		}
		if( server != null ) {
			server.stop();
			long end = System.currentTimeMillis()+10000;
			while( server.isRunning() && System.currentTimeMillis() < end ) {
				Thread.sleep(20);
			}
			assertFalse(server.isRunning());
			assertTrue(server.awaitStopped(10000));
		}
		if( mem != null ) {
			mem.disConnect();
		}
		for (String p : properties) {
			System.clearProperty(p);
		}
	}

	// ------------------------------------------------------------------ helpers

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	/** SETCLIENTID and SETCLIENTID_CONFIRM: the client ID */
	private static long clientid(TestClient c, String name) throws IOException {
		Ops ops = new Ops().op(Nfs4.OP_SETCLIENTID);
		ops.args().writeFixedOpaque(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
		ops.args().writeOpaque(bytes(name));
		ops.args().writeInt(0x40000000).writeString("tcp").writeString("127.0.0.1.0.0").writeInt(1);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_SETCLIENTID);
		long id = r.in.readLong();
		byte[] verf = r.in.readFixedOpaque(8);
		Ops conf = new Ops().op(Nfs4.OP_SETCLIENTID_CONFIRM);
		conf.args().writeLong(id).writeFixedOpaque(verf);
		assertEquals(Nfs4.NFS4_OK, c.compound(conf).status);
		return id;
	}

	private static final class Opened {
		int status;
		int seqid;
		byte[] other;
		int rflags;
		byte[] fh;

		void write(XdrEncoder e) {
			e.writeInt(seqid).writeFixedOpaque(other);
		}
	}

	private static void openArgs(Ops ops, int seqid, int access, int deny, long clientid, String owner, boolean create, String name) {
		ops.op(Nfs4.OP_OPEN);
		XdrEncoder a = ops.args();
		a.writeInt(seqid).writeInt(access).writeInt(deny).writeLong(clientid).writeOpaque(bytes(owner));
		if( create ) {
			a.writeInt(Nfs4.OPEN4_CREATE).writeInt(Nfs4.UNCHECKED4).writeIntArray(new int[0]).writeInt(0);
		} else {
			a.writeInt(Nfs4.OPEN4_NOCREATE);
		}
		a.writeInt(Nfs4.CLAIM_NULL).writeString(name);
	}

	/** PUTROOTFH OPEN GETFH */
	private static Opened open(TestClient c, int seqid, int access, int deny, long clientid, String owner, boolean create, String name)
			throws IOException {
		Ops ops = new Ops().putroot();
		openArgs(ops, seqid, access, deny, clientid, owner, create, name);
		ops.getfh();
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTROOTFH);
		Opened ret = new Opened();
		ret.status = r.next(Nfs4.OP_OPEN);
		if( ret.status != Nfs4.NFS4_OK ) {
			return ret;
		}
		ret.seqid = r.in.readInt();
		ret.other = r.in.readFixedOpaque(12);
		r.in.readBoolean();
		r.in.readLong();
		r.in.readLong();
		ret.rflags = r.in.readInt();
		r.in.readIntArray(8);
		assertEquals(Nfs4.OPEN_DELEGATE_NONE, r.in.readInt());
		r.ok(Nfs4.OP_GETFH);
		ret.fh = r.in.readOpaque(128);
		return ret;
	}

	private static void confirm(TestClient c, Opened o, int seqid) throws IOException {
		Ops ops = new Ops().putfh(o.fh).op(Nfs4.OP_OPEN_CONFIRM);
		o.write(ops.args());
		ops.args().writeInt(seqid);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_OPEN_CONFIRM);
		o.seqid = r.in.readInt();
		o.other = r.in.readFixedOpaque(12);
	}

	private static int write(TestClient c, byte[] fh, Opened o, long offset, byte[] data, int stable) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_WRITE);
		if( o == null ) {
			ops.args().writeInt(0).writeFixedOpaque(new byte[12]);
		} else {
			o.write(ops.args());
		}
		ops.args().writeLong(offset).writeInt(stable).writeOpaque(data);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		int s = r.next(Nfs4.OP_WRITE);
		if( s == Nfs4.NFS4_OK ) {
			assertEquals(data.length, r.in.readInt());
		}
		return s;
	}

	private static byte[] read(TestClient c, byte[] fh, long offset, int count) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_READ);
		ops.args().writeInt(0).writeFixedOpaque(new byte[12]).writeLong(offset).writeInt(count);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_READ);
		r.in.readBoolean();
		return r.in.readOpaque(Integer.MAX_VALUE);
	}

	private static int close(TestClient c, byte[] fh, Opened o, int seqid) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_CLOSE);
		ops.args().writeInt(seqid);
		o.write(ops.args());
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_CLOSE);
	}

	private static long size(TestClient c, byte[] fh) throws IOException {
		Result r = c.compound(new Ops().putfh(fh).getattr(Nfs4.FATTR4_SIZE));
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_GETATTR);
		r.in.readIntArray(8);
		r.in.readInt();
		return r.in.readLong();
	}

	private static List<String> readdir(TestClient c, byte[] dir, int maxcount) throws IOException {
		List<String> ret = new ArrayList<String>();
		long cookie = 0;
		while( true ) {
			Ops ops = new Ops().putfh(dir).op(Nfs4.OP_READDIR);
			ops.args().writeLong(cookie).writeFixedOpaque(new byte[8]).writeInt(maxcount).writeInt(maxcount)
				.writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_TYPE, Nfs4.FATTR4_FILEID));
			Result r = c.compound(ops);
			r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_READDIR);
			r.in.readFixedOpaque(8);
			while( r.in.readBoolean() ) {
				cookie = r.in.readLong();
				ret.add(r.in.readString(1024));
				r.in.readIntArray(8);
				r.in.readOpaque(1024);
			}
			if( r.in.readBoolean() ) {
				return ret;
			}
		}
	}

	private static byte[] rootFh(TestClient c) throws IOException {
		Result r = c.compound(new Ops().putroot().getfh());
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_GETFH);
		return r.in.readOpaque(128);
	}

	// ------------------------------------------------------------------ tests

	@Test
	public void rpcBasics() throws Exception {
		TestClient c = start("test.nfs.rpc", false);
		c.nfs(Nfs4.PROC_NULL, null);

		XdrDecoder r = c.call(Nfs4.PROGRAM, 3, 0, null);
		assertEquals(Rpc.MSG_ACCEPTED, r.readInt());
		r.readInt();
		r.readOpaque(400);
		assertEquals(Rpc.PROG_MISMATCH, r.readInt());
		assertEquals(4, r.readInt());
		assertEquals(4, r.readInt());

		r = c.call(100005, 3, 0, null);
		r.readInt();
		r.readInt();
		r.readOpaque(400);
		assertEquals(Rpc.PROG_UNAVAIL, r.readInt());

		r = c.call(Nfs4.PROGRAM, 4, 7, null);
		r.readInt();
		r.readInt();
		r.readOpaque(400);
		assertEquals(Rpc.PROC_UNAVAIL, r.readInt());

		Result res = c.compound(new Ops().putroot(), 1);
		assertEquals(Nfs4.NFS4ERR_MINOR_VERS_MISMATCH, res.status);
		assertEquals(0, res.count);

		// a call split into fragments
		byte[] call = c.callBytes(77, Nfs4.PROGRAM, 4, 0, null);
		byte[] body = Arrays.copyOfRange(call, 4, call.length);
		XdrEncoder frags = new XdrEncoder();
		frags.writeInt(10).writeRaw(body, 0, 10);
		frags.writeInt(RecordMarkingDecoder.LAST_FRAGMENT | (body.length-10)).writeRaw(body, 10, body.length-10);
		c.sendRaw(frags.toByteArray());
		r = c.readRecord();
		assertEquals(77, r.readInt());

		// a COMPOUND cut short
		XdrEncoder bad = new XdrEncoder();
		bad.writeString("t");
		r = c.call(Nfs4.PROGRAM, 4, 1, bad);
		r.readInt();
		r.readInt();
		r.readOpaque(400);
		assertEquals(Rpc.GARBAGE_ARGS, r.readInt());

		// an undefined operation
		res = c.compound(new Ops().putroot().op(9999));
		assertEquals(Nfs4.NFS4ERR_OP_ILLEGAL, res.status);
		res.ok(Nfs4.OP_PUTROOTFH);
		assertEquals(Nfs4.NFS4ERR_OP_ILLEGAL, res.next(Nfs4.OP_ILLEGAL));

		// no current file handle
		res = c.compound(new Ops().getfh());
		assertEquals(Nfs4.NFS4ERR_NOFILEHANDLE, res.status);
	}

	@Test
	public void rootAttributes() throws Exception {
		TestClient c = start("test.nfs.attr", false);
		Result r = c.compound(new Ops().putroot().getattr(Nfs4.FATTR4_SUPPORTED_ATTRS, Nfs4.FATTR4_TYPE,
				Nfs4.FATTR4_FSID, Nfs4.FATTR4_LEASE_TIME, Nfs4.FATTR4_ACL));
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_GETATTR);
		int[] bits = r.in.readIntArray(8);
		// ACL isn't supported, so it isn't returned
		assertFalse(us.bringardner.parley.nfs.server.Attributes.isSet(bits, Nfs4.FATTR4_ACL));
		XdrDecoder v = new XdrDecoder(r.in.readOpaque(4096));
		int[] supported = v.readIntArray(8);
		assertTrue(us.bringardner.parley.nfs.server.Attributes.isSet(supported, Nfs4.FATTR4_FILEHANDLE));
		assertEquals(Nfs4.NF4DIR, v.readInt());
		v.readLong();
		v.readLong();
		assertEquals(90, v.readInt());
		assertEquals(0, v.remaining());
	}

	@Test
	public void fileLifeCycle() throws Exception {
		TestClient c = start("test.nfs.life", false);
		long id = clientid(c, "client-one");
		Opened o = open(c, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, Nfs4.OPEN4_SHARE_DENY_NONE, id, "owner", true, "data.bin");
		assertEquals(Nfs4.NFS4_OK, o.status);
		assertTrue((o.rflags & Nfs4.OPEN4_RESULT_CONFIRM) != 0);
		// not usable before OPEN_CONFIRM
		assertEquals(Nfs4.NFS4ERR_BAD_STATEID, write(c, o.fh, o, 0, new byte[1], Nfs4.UNSTABLE4));
		confirm(c, o, 2);

		byte[] data = new byte[300000];
		new Random(1).nextBytes(data);
		assertEquals(Nfs4.NFS4_OK, write(c, o.fh, o, 0, Arrays.copyOf(data, 200000), Nfs4.UNSTABLE4));
		assertEquals(Nfs4.NFS4_OK, write(c, o.fh, o, 200000, Arrays.copyOfRange(data, 200000, data.length), Nfs4.FILE_SYNC4));
		Ops commit = new Ops().putfh(o.fh).op(Nfs4.OP_COMMIT);
		commit.args().writeLong(0).writeInt(0);
		Result r = c.compound(commit);
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_COMMIT);
		assertEquals(data.length, size(c, o.fh));
		byte[] back = new byte[0];
		while( back.length < data.length ) {
			byte[] part = read(c, o.fh, back.length, 65536);
			assertTrue(part.length > 0);
			byte[] n = Arrays.copyOf(back, back.length+part.length);
			System.arraycopy(part, 0, n, back.length, part.length);
			back = n;
		}
		assertArrayEquals(data, back);
		assertEquals(0, read(c, o.fh, data.length, 10).length);

		assertEquals(Nfs4.NFS4_OK, close(c, o.fh, o, 3));
		// a retransmitted CLOSE gets the same answer
		assertEquals(Nfs4.NFS4_OK, close(c, o.fh, o, 3));
		assertEquals(Nfs4.NFS4ERR_BAD_SEQID, close(c, o.fh, o, 9));

		// the data reached the FileSource
		FileSource f = root.getChild("data.bin");
		assertEquals(data.length, f.length());

		byte[] dir = rootFh(c);
		assertEquals(Arrays.asList("data.bin"), readdir(c, dir, 4096));

		// SAVEFH, RENAME
		Ops ops = new Ops().putroot().op(Nfs4.OP_SAVEFH).op(Nfs4.OP_RENAME);
		ops.args().writeString("data.bin").writeString("moved.bin");
		r = c.compound(ops);
		assertEquals(Nfs4.NFS4_OK, r.status);
		assertFalse(f.exists());
		assertTrue(root.getChild("moved.bin").exists());
		// the handle still names the file
		assertEquals(data.length, size(c, o.fh));

		// REMOVE, then the handle is stale
		ops = new Ops().putroot().op(Nfs4.OP_REMOVE);
		ops.args().writeString("moved.bin");
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		r = c.compound(new Ops().putfh(o.fh).getattr(Nfs4.FATTR4_SIZE));
		assertEquals(Nfs4.NFS4ERR_STALE, r.status);
		r = c.compound(new Ops().putroot().lookup("moved.bin"));
		assertEquals(Nfs4.NFS4ERR_NOENT, r.status);
		// a handle that isn't one of ours
		r = c.compound(new Ops().putfh(new byte[] {9, 9, 9}));
		assertEquals(Nfs4.NFS4ERR_BADHANDLE, r.status);
	}

	@Test
	public void foldersAndNames() throws Exception {
		TestClient c = start("test.nfs.dirs", false);
		Ops ops = new Ops().putroot().op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4DIR).writeString("sub").writeIntArray(new int[0]).writeInt(0);
		ops.getfh();
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_CREATE);
		r.in.readBoolean();
		long before = r.in.readLong();
		long after = r.in.readLong();
		assertNotEquals(before, after);
		r.in.readIntArray(8);
		r.ok(Nfs4.OP_GETFH);
		byte[] sub = r.in.readOpaque(128);
		assertTrue(root.getChild("sub").isDirectory());

		ops = new Ops().putroot().op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4DIR).writeString("sub").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4ERR_EXIST, c.compound(ops).status);

		for (String bad : new String[] {"..", ".", "a/b"}) {
			assertEquals(Nfs4.NFS4ERR_BADNAME, c.compound(new Ops().putroot().lookup(bad)).status);
		}
		assertEquals(Nfs4.NFS4ERR_INVAL, c.compound(new Ops().putroot().lookup("")).status);

		// a file in sub, then sub can't be removed
		root.getChild("sub").getChild("x").createNewFile();
		ops = new Ops().putroot().op(Nfs4.OP_REMOVE);
		ops.args().writeString("sub");
		assertEquals(Nfs4.NFS4ERR_NOTEMPTY, c.compound(ops).status);

		// LOOKUPP goes back to the root
		r = c.compound(new Ops().putfh(sub).op(Nfs4.OP_LOOKUPP).getfh());
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_LOOKUPP).ok(Nfs4.OP_GETFH);
		assertArrayEquals(rootFh(c), r.in.readOpaque(128));
		assertEquals(Nfs4.NFS4ERR_NOENT, c.compound(new Ops().putroot().op(Nfs4.OP_LOOKUPP)).status);

		// READDIR in pages
		for (int i = 0; i < 250; i++) {
			root.getChild("sub").getChild(String.format("f%03d", i)).createNewFile();
		}
		List<String> names = readdir(c, sub, 2048);
		assertEquals(251, names.size());
		assertEquals("f000", names.get(0));
		assertEquals("x", names.get(250));
		Ops tiny = new Ops().putfh(sub).op(Nfs4.OP_READDIR);
		tiny.args().writeLong(0).writeFixedOpaque(new byte[8]).writeInt(20).writeInt(20).writeIntArray(new int[0]);
		assertEquals(Nfs4.NFS4ERR_TOOSMALL, c.compound(tiny).status);

		// regular files can't be made with CREATE
		ops = new Ops().putroot().op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4REG).writeString("r").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4ERR_BADTYPE, c.compound(ops).status);
	}

	@Test
	public void shareReservationsAndSeqids() throws Exception {
		TestClient c = start("test.nfs.share", false);
		long id = clientid(c, "client-two");
		Opened a = open(c, 1, Nfs4.OPEN4_SHARE_ACCESS_READ, Nfs4.OPEN4_SHARE_DENY_WRITE, id, "a", true, "f");
		assertEquals(Nfs4.NFS4_OK, a.status);
		confirm(c, a, 2);

		Opened b = open(c, 1, Nfs4.OPEN4_SHARE_ACCESS_WRITE, Nfs4.OPEN4_SHARE_DENY_NONE, id, "b", false, "f");
		assertEquals(Nfs4.NFS4ERR_SHARE_DENIED, b.status);
		assertEquals(Nfs4.NFS4ERR_LOCKED, write(c, a.fh, null, 0, new byte[3], Nfs4.FILE_SYNC4));
		// a READ open can't write
		assertEquals(Nfs4.NFS4ERR_OPENMODE, write(c, a.fh, a, 0, new byte[3], Nfs4.FILE_SYNC4));

		// the same OPEN sent twice: the second is answered from the saved reply
		Opened b1 = open(c, 3, Nfs4.OPEN4_SHARE_ACCESS_READ, Nfs4.OPEN4_SHARE_DENY_NONE, id, "a", false, "f");
		Opened b2 = open(c, 3, Nfs4.OPEN4_SHARE_ACCESS_READ, Nfs4.OPEN4_SHARE_DENY_NONE, id, "a", false, "f");
		assertEquals(Nfs4.NFS4_OK, b1.status);
		assertEquals(b1.seqid, b2.seqid);
		assertArrayEquals(b1.other, b2.other);
		assertArrayEquals(a.other, b1.other);
		// an old seqid
		assertEquals(Nfs4.NFS4ERR_BAD_SEQID, open(c, 1, Nfs4.OPEN4_SHARE_ACCESS_READ, 0, id, "a", false, "f").status);

		// OPEN_DOWNGRADE: deny none, then b may write
		Ops ops = new Ops().putfh(a.fh).op(Nfs4.OP_OPEN_DOWNGRADE);
		ops.args().writeInt(b1.seqid).writeFixedOpaque(b1.other).writeInt(4)
			.writeInt(Nfs4.OPEN4_SHARE_ACCESS_READ).writeInt(Nfs4.OPEN4_SHARE_DENY_NONE);
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		assertEquals(Nfs4.NFS4_OK, write(c, a.fh, null, 0, new byte[3], Nfs4.FILE_SYNC4));

		// stateids that aren't valid
		Opened fake = new Opened();
		fake.seqid = 1;
		fake.other = new byte[12];
		fake.other[0] = 1;
		assertEquals(Nfs4.NFS4ERR_STALE_STATEID, write(c, a.fh, fake, 0, new byte[1], Nfs4.FILE_SYNC4));
		Opened old = new Opened();
		old.seqid = 1;
		old.other = a.other;
		assertEquals(Nfs4.NFS4ERR_OLD_STATEID, write(c, a.fh, old, 0, new byte[1], Nfs4.FILE_SYNC4));

		// an unknown client ID
		ops = new Ops().op(Nfs4.OP_RENEW);
		ops.args().writeLong(12345);
		assertEquals(Nfs4.NFS4ERR_STALE_CLIENTID, c.compound(ops).status);
		ops = new Ops().op(Nfs4.OP_RENEW);
		ops.args().writeLong(id);
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
	}

	@Test
	public void setattrAndVerify() throws Exception {
		TestClient c = start("test.nfs.setattr", false);
		FileSource f = root.getChild("s");
		f.createNewFile();
		try (java.io.OutputStream out = f.getOutputStream()) {
			out.write(new byte[1000]);
		}
		Result r = c.compound(new Ops().putroot().lookup("s").getfh());
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_LOOKUP).ok(Nfs4.OP_GETFH);
		byte[] fh = r.in.readOpaque(128);

		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_SETATTR);
		XdrEncoder vals = new XdrEncoder().writeLong(10);
		ops.args().writeInt(0).writeFixedOpaque(new byte[12])
			.writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_SIZE)).writeOpaque(vals.toByteArray());
		r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_SETATTR);
		assertEquals(10, size(c, fh));
		assertEquals(10, f.length());

		// VERIFY the size, NVERIFY it
		XdrEncoder ten = new XdrEncoder().writeLong(10);
		ops = new Ops().putfh(fh).op(Nfs4.OP_VERIFY);
		ops.args().writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_SIZE)).writeOpaque(ten.toByteArray());
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		ops = new Ops().putfh(fh).op(Nfs4.OP_NVERIFY);
		ops.args().writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_SIZE)).writeOpaque(ten.toByteArray());
		assertEquals(Nfs4.NFS4ERR_SAME, c.compound(ops).status);

		// a read-only attribute can't be set; SETATTR still returns attrsset
		ops = new Ops().putfh(fh).op(Nfs4.OP_SETATTR);
		ops.args().writeInt(0).writeFixedOpaque(new byte[12])
			.writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_FILEID)).writeOpaque(new XdrEncoder().writeLong(1).toByteArray());
		r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		assertEquals(Nfs4.NFS4ERR_INVAL, r.next(Nfs4.OP_SETATTR));
		assertEquals(0, r.in.readIntArray(8).length);
	}

	@Test
	public void readOnlyExport() throws Exception {
		TestClient c = start("test.nfs.ro", true);
		Ops ops = new Ops().putroot().op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4DIR).writeString("sub").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4ERR_ROFS, c.compound(ops).status);
		Result r = c.compound(new Ops().putroot().op(Nfs4.OP_ACCESS));
		// no arguments: BADXDR
		assertEquals(Nfs4.NFS4ERR_BADXDR, r.status);
		ops = new Ops().putroot().op(Nfs4.OP_ACCESS);
		ops.args().writeInt(0x3f);
		r = c.compound(ops);
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_ACCESS);
		// EXECUTE means nothing for a folder (RFC 7530 section 16.1.4)
		assertEquals(0x3f & ~Nfs4.ACCESS4_EXECUTE, r.in.readInt());
		assertEquals(Nfs4.ACCESS4_READ | Nfs4.ACCESS4_LOOKUP, r.in.readInt());
	}

	/** Problems pynfs (the Linux NFS server tests) found. */
	@Test
	public void pynfsFindings() throws Exception {
		TestClient c = start("test.nfs.pynfs", false);
		long id = clientid(c, "client-pynfs");

		// OPEN creating a file with a size (WRT1, OPEN2)
		XdrEncoder sized = new XdrEncoder();
		sized.writeInt(1).writeInt(Nfs4.OPEN4_SHARE_ACCESS_BOTH).writeInt(Nfs4.OPEN4_SHARE_DENY_NONE).writeLong(id)
			.writeOpaque(bytes("sized")).writeInt(Nfs4.OPEN4_CREATE).writeInt(Nfs4.UNCHECKED4)
			.writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_SIZE));
		XdrEncoder size32 = new XdrEncoder();
		size32.writeLong(32);
		sized.writeOpaque(size32.toByteArray()).writeInt(Nfs4.CLAIM_NULL).writeString("sized");
		Ops ops = new Ops().putroot().op(Nfs4.OP_OPEN);
		ops.args().write(sized);
		ops.getfh();
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_OPEN);
		Opened s = new Opened();
		s.seqid = r.in.readInt();
		s.other = r.in.readFixedOpaque(12);
		r.in.readBoolean();
		r.in.readLong();
		r.in.readLong();
		r.in.readInt();
		assertTrue(us.bringardner.parley.nfs.server.Attributes.isSet(r.in.readIntArray(8), Nfs4.FATTR4_SIZE));
		r.in.readInt();
		r.ok(Nfs4.OP_GETFH);
		s.fh = r.in.readOpaque(128);
		confirm(c, s, 2);
		assertEquals(32, size(c, s.fh));
		assertEquals(Nfs4.NFS4_OK, write(c, s.fh, s, 0, bytes("data"), Nfs4.UNSTABLE4));
		byte[] got = read(c, s.fh, 0, 20);
		assertEquals(20, got.length);
		assertArrayEquals(bytes("data"), Arrays.copyOf(got, 4));
		// UNCHECKED4 of an existing file ignores a size other than 0 (OPEN2)
		ops = new Ops().putroot().op(Nfs4.OP_OPEN);
		XdrEncoder again = new XdrEncoder();
		size32 = new XdrEncoder();
		size32.writeLong(16);
		again.writeInt(3).writeInt(Nfs4.OPEN4_SHARE_ACCESS_BOTH).writeInt(Nfs4.OPEN4_SHARE_DENY_NONE).writeLong(id)
			.writeOpaque(bytes("sized")).writeInt(Nfs4.OPEN4_CREATE).writeInt(Nfs4.UNCHECKED4)
			.writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_SIZE))
			.writeOpaque(size32.toByteArray()).writeInt(Nfs4.CLAIM_NULL).writeString("sized");
		ops.args().write(again);
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		assertEquals(32, size(c, s.fh));

		// the owner's own open denies it too (OPEN21, OPEN25)
		Opened d = open(c, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, Nfs4.OPEN4_SHARE_DENY_READ, id, "deny", true, "denied");
		assertEquals(Nfs4.NFS4_OK, d.status);
		confirm(c, d, 2);
		assertEquals(Nfs4.NFS4ERR_SHARE_DENIED,
				open(c, 3, Nfs4.OPEN4_SHARE_ACCESS_READ, Nfs4.OPEN4_SHARE_DENY_NONE, id, "deny", false, "denied").status);

		// OPEN_DOWNGRADE to a pair no OPEN asked for (OPDG2): the failed OPEN used seqid 3
		ops = new Ops().putfh(d.fh).op(Nfs4.OP_OPEN_DOWNGRADE);
		d.write(ops.args());
		ops.args().writeInt(4).writeInt(Nfs4.OPEN4_SHARE_ACCESS_READ).writeInt(Nfs4.OPEN4_SHARE_DENY_READ);
		assertEquals(Nfs4.NFS4ERR_INVAL, c.compound(ops).status);

		// LOCK isn't supported, but counts as the open-owner's seqid 5 (LOCK16, OPDG11)
		ops = new Ops().putfh(d.fh).op(Nfs4.OP_LOCK);
		ops.args().writeInt(1 /* READ_LT */).writeBoolean(false).writeLong(0).writeLong(10).writeBoolean(true).writeInt(5);
		d.write(ops.args());
		ops.args().writeInt(0).writeLong(id).writeOpaque(bytes("lock-owner"));
		r = c.compound(ops);
		assertEquals(Nfs4.NFS4ERR_NOTSUPP, r.status);
		assertEquals(Nfs4.NFS4_OK, close(c, d.fh, d, 6));

		// ACCESS of a file: LOOKUP and DELETE mean nothing (ACC2r)
		ops = new Ops().putfh(s.fh).op(Nfs4.OP_ACCESS);
		ops.args().writeInt(0x3f);
		r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_ACCESS);
		assertEquals(0x3f & ~(Nfs4.ACCESS4_LOOKUP | Nfs4.ACCESS4_DELETE), r.in.readInt());

		// GETATTR: write-only attributes are NFS4ERR_INVAL (GATT3), unknown ones are left out (GATT4)
		assertEquals(Nfs4.NFS4ERR_INVAL, c.compound(new Ops().putfh(s.fh).getattr(Nfs4.FATTR4_TIME_ACCESS_SET)).status);
		r = c.compound(new Ops().putfh(s.fh).getattr(Nfs4.FATTR4_SIZE, 1000));
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_GETATTR);
		assertArrayEquals(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_SIZE), r.in.readIntArray(8));

		// READDIR: too small for an empty list (RDDR8), a write-only attribute (RDDR9)
		ops = new Ops().putroot().op(Nfs4.OP_READDIR);
		ops.args().writeLong(0).writeFixedOpaque(new byte[8]).writeInt(15).writeInt(15).writeIntArray(new int[0]);
		assertEquals(Nfs4.NFS4ERR_TOOSMALL, c.compound(ops).status);
		ops = new Ops().putroot().op(Nfs4.OP_READDIR);
		ops.args().writeLong(0).writeFixedOpaque(new byte[8]).writeInt(4096).writeInt(4096)
			.writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_TIME_MODIFY_SET));
		assertEquals(Nfs4.NFS4ERR_INVAL, c.compound(ops).status);

		// COMMIT past 2^64 (CMT4)
		ops = new Ops().putfh(s.fh).op(Nfs4.OP_COMMIT);
		ops.args().writeLong(0xfffffffffffffff0L).writeInt(64);
		assertEquals(Nfs4.NFS4ERR_INVAL, c.compound(ops).status);

		// a CREATE sent again gets its saved reply, not NFS4ERR_EXIST (RPLY14)
		ops = new Ops().putroot().op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4DIR).writeString("again").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		assertEquals(Nfs4.NFS4_OK, c.compoundAgain(ops).status);
		assertEquals(Nfs4.NFS4ERR_EXIST, c.compound(ops).status);
		ops = new Ops().putroot().op(Nfs4.OP_REMOVE);
		ops.args().writeString("again");
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		assertEquals(Nfs4.NFS4_OK, c.compoundAgain(ops).status);
		assertEquals(Nfs4.NFS4ERR_NOENT, c.compound(ops).status);
	}

	@Test
	public void registryChecksUsers(@TempDir Path tmp) throws Exception {
		Path idmap = tmp.resolve("idmap.properties");
		Files.write(idmap, "uid.1000=tony\nuid.1001=pat\n".getBytes(StandardCharsets.UTF_8));
		property("test.nfs.iam.idmap", idmap.toString());
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		root.mkdirs();
		Export export = new Export("test", root, false);
		BasicUserRegistry registry = new BasicUserRegistry(1000);
		ParleyPrincipal tony = registry.createUser("tony");
		registry.grant(tony, new BasicParleyPermission(StandardPermissions.READ, export.getId()));
		registry.createUser("pat");
		server = new NfsServer("test.nfs.iam", 0);
		server.setExport(export);
		server.setUserRegistry(registry);
		server.startAndWait(10000);

		TestClient c = client();
		c.uid = 1000;
		assertEquals(Nfs4.NFS4_OK, c.compound(new Ops().putroot().getattr(Nfs4.FATTR4_TYPE)).status);
		Ops mk = new Ops().putroot().op(Nfs4.OP_CREATE);
		mk.args().writeInt(Nfs4.NF4DIR).writeString("sub").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4ERR_ACCESS, c.compound(mk).status);

		c.uid = 1001; // pat: no permissions
		assertEquals(Nfs4.NFS4ERR_ACCESS, c.compound(new Ops().putroot().getattr(Nfs4.FATTR4_TYPE)).status);
		c.uid = 0; // root isn't mapped and there is no anonymous user
		assertEquals(Nfs4.NFS4ERR_ACCESS, c.compound(new Ops().putroot().getattr(Nfs4.FATTR4_TYPE)).status);

		c.credFlavor = Rpc.AUTH_NONE;
		XdrDecoder r = c.call(Nfs4.PROGRAM, 4, 0, null);
		assertEquals(Rpc.MSG_DENIED, r.readInt());
		assertEquals(Rpc.AUTH_ERROR, r.readInt());
		assertEquals(Rpc.AUTH_TOOWEAK, r.readInt());

		registry.grant(tony, new BasicParleyPermission(StandardPermissions.WRITE, export.getId()));
		server.getAuthorizer().clear();
		TestClient t = client();
		t.uid = 1000;
		assertEquals(Nfs4.NFS4_OK, t.compound(mk).status);
	}

	@Test
	public void leaseExpires() throws Exception {
		property("test.nfs.lease.leaseTime", "1s");
		TestClient c = start("test.nfs.lease", false);
		long id = clientid(c, "client-three");
		Opened o = open(c, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, 0, id, "o", true, "f");
		confirm(c, o, 2);
		Thread.sleep(2500);
		assertEquals(Nfs4.NFS4ERR_EXPIRED, write(c, o.fh, o, 0, new byte[1], Nfs4.FILE_SYNC4));
		Ops ops = new Ops().op(Nfs4.OP_RENEW);
		ops.args().writeLong(id);
		assertEquals(Nfs4.NFS4ERR_EXPIRED, c.compound(ops).status);
		// the client starts over
		long id2 = clientid(c, "client-three");
		assertNotEquals(id, id2);
		assertEquals(Nfs4.NFS4_OK, open(c, 1, Nfs4.OPEN4_SHARE_ACCESS_READ, 0, id2, "o", false, "f").status);
	}
}
