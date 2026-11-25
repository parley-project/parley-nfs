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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
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
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.files.DelegatingFileSource;
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
import us.bringardner.parley.nfs.server.Authorizer;
import us.bringardner.parley.nfs.server.Caller;
import us.bringardner.parley.nfs.server.NfsServer;
import us.bringardner.parley.net.server.KnownSettings;

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

		Result res = c.compound(new Ops().putroot(), 3);
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

	/** df: space comes from the export's FileSource; what it can't tell or fails to answer is 0 (decision D24). */
	@Test
	public void spaceAttributes() throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		FileSource spaced = new DelegatingFileSource(root) {
			@Override
			public long getTotalSpace() {
				return 1000L;
			}

			@Override
			public long getFreeSpace() {
				return 400L;
			}

			@Override
			public long getUsableSpace() throws IOException {
				throw new IOException("can't tell");
			}
		};
		server = new NfsServer("test.nfs.space", 0);
		server.setExport(new Export("test", spaced, false));
		server.startAndWait(10000);
		TestClient c = client();

		Result r = c.compound(new Ops().putroot().getattr(Nfs4.FATTR4_SPACE_AVAIL, Nfs4.FATTR4_SPACE_FREE,
				Nfs4.FATTR4_SPACE_TOTAL));
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_GETATTR);
		int[] bits = r.in.readIntArray(8);
		assertTrue(us.bringardner.parley.nfs.server.Attributes.isSet(bits, Nfs4.FATTR4_SPACE_AVAIL));
		assertTrue(us.bringardner.parley.nfs.server.Attributes.isSet(bits, Nfs4.FATTR4_SPACE_FREE));
		assertTrue(us.bringardner.parley.nfs.server.Attributes.isSet(bits, Nfs4.FATTR4_SPACE_TOTAL));
		XdrDecoder v = new XdrDecoder(r.in.readOpaque(4096));
		assertEquals(0L, v.readLong(), "a FileSource that fails to answer is 0");
		assertEquals(400L, v.readLong());
		assertEquals(1000L, v.readLong());
		assertEquals(0, v.remaining());
	}

	/** A file system whose factory refuses links with an UnsupportedOperationException gives PERM, not a failed call. */
	@Test
	public void aFactoryThatRefusesLinksGivesPerm() throws Exception {
		mem = new MemoryFileSourceFactory() {
			private static final long serialVersionUID = 1L;

			@Override
			public FileSource createSymbolicLink(FileSource newLink, String linkText) throws IOException {
				throw new UnsupportedOperationException("no links here");
			}
		};
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer("test.nfs.no-links", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		TestClient c = client();
		Ops ops = new Ops().putroot().op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4LNK).writeOpaque(bytes("target")).writeString("sym").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4ERR_PERM, c.compound(ops).status);
		assertFalse(root.getChild("sym").exists());
	}

	/** The same on a local folder: removing a link to a folder takes the link and leaves the folder, and a link isn't opened. */
	@Test
	public void symbolicLinksOnLocalFiles(@TempDir Path tmp) throws Exception {
		Path probe = tmp.resolve("probe");
		try {
			Files.createSymbolicLink(probe, tmp);
		} catch (IOException | UnsupportedOperationException e) {
			org.junit.jupiter.api.Assumptions.assumeTrue(false, "no symbolic links here: " + e);
		}
		Files.delete(probe);
		Files.createDirectories(tmp.resolve("folder"));
		Files.write(tmp.resolve("folder").resolve("keep.txt"), bytes("keep"));
		root = new us.bringardner.parley.files.fileproxy.FileProxyFactory().createFileSource(tmp.toString());
		server = new NfsServer("test.nfs.local-links", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		TestClient c = client();
		long id = clientid(c, "client-links");

		Ops ops = new Ops().putroot().op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4LNK).writeOpaque(bytes("folder")).writeString("to-folder").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		ops = new Ops().putroot().op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4LNK).writeOpaque(bytes("../outside")).writeString("up").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		// what a Linux client asks of every name in a folder, a link to nothing included
		for (String n : new String[] {"to-folder", "up"}) {
			assertEquals(Nfs4.NFS4_OK, c.compound(new Ops().putroot().lookup(n)).status, "LOOKUP of " + n);
			Result r = c.compound(new Ops().putroot().lookup(n).getattr(Nfs4.FATTR4_TYPE, Nfs4.FATTR4_CHANGE, Nfs4.FATTR4_SIZE,
					Nfs4.FATTR4_FSID, Nfs4.FATTR4_FILEID, Nfs4.FATTR4_MODE, Nfs4.FATTR4_NUMLINKS, Nfs4.FATTR4_OWNER,
					Nfs4.FATTR4_OWNER_GROUP, Nfs4.FATTR4_RAWDEV, Nfs4.FATTR4_SPACE_USED, Nfs4.FATTR4_TIME_ACCESS,
					Nfs4.FATTR4_TIME_METADATA, Nfs4.FATTR4_TIME_MODIFY, Nfs4.FATTR4_MOUNTED_ON_FILEID));
			assertEquals(Nfs4.NFS4_OK, r.status, "GETATTR of " + n);
			for (int bit : new int[] {Nfs4.FATTR4_TYPE, Nfs4.FATTR4_CHANGE, Nfs4.FATTR4_SIZE, Nfs4.FATTR4_FILEID, Nfs4.FATTR4_MODE,
					Nfs4.FATTR4_NUMLINKS, Nfs4.FATTR4_OWNER, Nfs4.FATTR4_OWNER_GROUP, Nfs4.FATTR4_SPACE_USED, Nfs4.FATTR4_TIME_ACCESS,
					Nfs4.FATTR4_TIME_METADATA, Nfs4.FATTR4_TIME_MODIFY, Nfs4.FATTR4_TIME_CREATE, Nfs4.FATTR4_HIDDEN}) {
				assertEquals(Nfs4.NFS4_OK, c.compound(new Ops().putroot().lookup(n).getattr(bit)).status, "attribute " + bit + " of " + n);
			}
		}
		assertEquals("folder", Files.readSymbolicLink(tmp.resolve("to-folder")).toString());
		assertEquals("../outside", Files.readSymbolicLink(tmp.resolve("up")).toString());

		// a folder is listed under its own name only, and a link isn't opened, even one to a folder
		assertEquals(Nfs4.NFS4ERR_SYMLINK, open(c, 1, Nfs4.OPEN4_SHARE_ACCESS_READ, Nfs4.OPEN4_SHARE_DENY_NONE, id, "o", false, "to-folder").status);
		assertEquals(Nfs4.NFS4ERR_SYMLINK, open(c, 1, Nfs4.OPEN4_SHARE_ACCESS_READ, Nfs4.OPEN4_SHARE_DENY_NONE, id, "o", false, "up").status);

		// REMOVE of the link to a folder that has files: the link goes, the folder and its file stay
		ops = new Ops().putroot().op(Nfs4.OP_REMOVE);
		ops.args().writeString("to-folder");
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		assertFalse(Files.isSymbolicLink(tmp.resolve("to-folder")));
		assertTrue(Files.exists(tmp.resolve("folder").resolve("keep.txt")));
		ops = new Ops().putroot().op(Nfs4.OP_REMOVE);
		ops.args().writeString("up");
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		assertFalse(Files.isSymbolicLink(tmp.resolve("up")));
	}

	/** What the server doesn't serve (links, devices, sockets, fifos) is refused with errors a Linux client can name (decision D24). */
	@Test
	public void unsupportedObjectsGiveNamedErrors() throws Exception {
		TestClient c = start("test.nfs.unsupported", false);
		Ops ops = new Ops().putroot().op(Nfs4.OP_SAVEFH).op(Nfs4.OP_LINK);
		ops.args().writeString("hard");
		assertEquals(Nfs4.NFS4ERR_XDEV, c.compound(ops).status, "hard links");

		ops = new Ops().putroot().op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4FIFO).writeString("pipe").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4ERR_PERM, c.compound(ops).status, "fifos");
		assertFalse(root.getChild("pipe").exists());
	}

	/** A link answers its own times, not those of what it leads to (D78). */
	@Test
	public void aLinkAnswersItsOwnTimes(@TempDir Path tmp) throws Exception {
		Path target = tmp.resolve("target.txt");
		Files.write(target, bytes("data"));
		Path link = tmp.resolve("ln");
		long oldTime = 1_000_000_000L;
		long newTime = 1_500_000_000L;
		try {
			Files.createSymbolicLink(link, target.getFileName());
			java.nio.file.attribute.BasicFileAttributeView v = Files.getFileAttributeView(link,
					java.nio.file.attribute.BasicFileAttributeView.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
			v.setTimes(java.nio.file.attribute.FileTime.fromMillis(newTime * 1000),
					java.nio.file.attribute.FileTime.fromMillis(newTime * 1000), null);
		} catch (IOException | UnsupportedOperationException e) {
			org.junit.jupiter.api.Assumptions.assumeTrue(false, "this system can't make a link or set its times: " + e);
		}
		Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(oldTime * 1000));
		Files.setAttribute(target, "lastAccessTime", java.nio.file.attribute.FileTime.fromMillis(oldTime * 1000));
		assertEquals(newTime, Files.getLastModifiedTime(link, java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis() / 1000);

		root = new us.bringardner.parley.files.fileproxy.FileProxyFactory().createFileSource(tmp.toString());
		server = new NfsServer("test.nfs.link-times", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		TestClient c = client();

		for (String name : new String[] {"ln", "target.txt"}) {
			long expected = name.equals("ln") ? newTime : oldTime;
			Result r = c.compound(new Ops().putroot().lookup(name).getattr(Nfs4.FATTR4_TIME_ACCESS, Nfs4.FATTR4_TIME_MODIFY));
			r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_LOOKUP).ok(Nfs4.OP_GETATTR);
			r.in.readIntArray(8);
			XdrDecoder v = new XdrDecoder(r.in.readOpaque(4096));
			// reading a link (its text) changes its access time on some systems, so that is checked only for the file
			long access = v.readLong();
			if( name.equals("target.txt") ) {
				assertEquals(expected, access, name + " access time");
			}
			v.readInt();
			assertEquals(expected, v.readLong(), name + " modify time");
		}
	}

	/**
	 * Symbolic links keep the text they were made with (decision D72): CREATE, READLINK, the type and size, LOOKUP,
	 * a link to nothing, no open or lookup through one, and REMOVE of the link and not of what it points to.
	 */
	@Test
	public void symbolicLinksKeepTheirText() throws Exception {
		TestClient c = start("test.nfs.symlinks", false);
		assertTrue(root.getChild("real").mkdirs());
		assertTrue(root.getChild("real").getChild("in").createNewFile());

		for (String text : new String[] {"real", "../up/and/down", "/an/absolute/path", "no/such/place"}) {
			String name = "l" + Math.abs(text.hashCode());
			Ops ops = new Ops().putroot().op(Nfs4.OP_CREATE);
			ops.args().writeInt(Nfs4.NF4LNK).writeOpaque(bytes(text)).writeString(name).writeIntArray(new int[0]).writeInt(0);
			ops.getfh();
			Result r = c.compound(ops);
			r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_CREATE);
			r.in.readBoolean();
			r.in.readLong();
			r.in.readLong();
			assertEquals(0, r.in.readIntArray(8).length);
			r.ok(Nfs4.OP_GETFH);
			byte[] fh = r.in.readOpaque(128);
			assertEquals(text, root.getChild(name).getLinkText());

			// READLINK gives the text back as it was
			r = c.compound(new Ops().putfh(fh).op(Nfs4.OP_READLINK));
			r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_READLINK);
			assertEquals(text, new String(r.in.readOpaque(4096), java.nio.charset.StandardCharsets.UTF_8));

			// the type is a link and the size is the length of the text, even for a link to nothing
			r = c.compound(new Ops().putfh(fh).getattr(Nfs4.FATTR4_TYPE, Nfs4.FATTR4_SIZE));
			r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_GETATTR);
			r.in.readIntArray(8);
			XdrDecoder v = new XdrDecoder(r.in.readOpaque(4096));
			assertEquals(Nfs4.NF4LNK, v.readInt(), text);
			assertEquals((long) bytes(text).length, v.readLong(), text);

			// LOOKUP finds it (a link to nothing too), and nothing can be looked up in it
			r = c.compound(new Ops().putroot().lookup(name).op(Nfs4.OP_READLINK));
			r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_LOOKUP).ok(Nfs4.OP_READLINK);
			ops = new Ops().putfh(fh).lookup("in");
			assertEquals(Nfs4.NFS4ERR_SYMLINK, c.compound(ops).status, text);
		}

		// a name that is taken
		Ops ops = new Ops().putroot().op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4LNK).writeOpaque(bytes("other")).writeString("real").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4ERR_EXIST, c.compound(ops).status);
		// READLINK of what isn't a link
		assertEquals(Nfs4.NFS4ERR_INVAL, c.compound(new Ops().putroot().lookup("real").op(Nfs4.OP_READLINK)).status);

		// REMOVE takes the link out and leaves the folder it points to
		String link = "l" + Math.abs("real".hashCode());
		ops = new Ops().putroot().op(Nfs4.OP_REMOVE);
		ops.args().writeString(link);
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		assertFalse(root.getChild(link).getLinkText() != null);
		assertTrue(root.getChild("real").getChild("in").exists());
		// and a link to nothing
		String dangling = "l" + Math.abs("no/such/place".hashCode());
		ops = new Ops().putroot().op(Nfs4.OP_REMOVE);
		ops.args().writeString(dangling);
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		assertNull(root.getChild(dangling).getLinkText());
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

	/**
	 * ACCESS for a file depends on the write bits of the file (any class), not on the user the server runs as
	 * (decision D71).
	 */
	@Test
	public void accessFollowsTheWriteBitsNotTheServerUser(@TempDir Path tmp) throws Exception {
		Files.write(tmp.resolve("ro"), bytes("x"));
		Files.write(tmp.resolve("rw"), bytes("x"));
		Files.setPosixFilePermissions(tmp.resolve("ro"), java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"));
		Files.setPosixFilePermissions(tmp.resolve("rw"), java.nio.file.attribute.PosixFilePermissions.fromString("r--r--rw-"));
		root = new us.bringardner.parley.files.fileproxy.FileProxyFactory().createFileSource(tmp.toString());
		server = new NfsServer("test.nfs.access-bits", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		TestClient c = client();
		int write = Nfs4.ACCESS4_MODIFY | Nfs4.ACCESS4_EXTEND;

		// FileProxy answers canOwnerWrite() with canWrite(), which is always true for root, so the no-write case
		// can be checked only when the tests do not run as root
		if( !"root".equals(System.getProperty("user.name")) ) {
			Ops ops = new Ops().putroot().lookup("ro").op(Nfs4.OP_ACCESS);
		ops.args().writeInt(Nfs4.ACCESS4_READ | write);
		Result r0 = c.compound(ops);
		r0.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_LOOKUP).ok(Nfs4.OP_ACCESS);
		assertEquals(Nfs4.ACCESS4_READ | write, r0.in.readInt());
		assertEquals(Nfs4.ACCESS4_READ, r0.in.readInt());
		}

		Ops ops = new Ops().putroot().lookup("rw").op(Nfs4.OP_ACCESS);
		ops.args().writeInt(Nfs4.ACCESS4_READ | write);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_LOOKUP).ok(Nfs4.OP_ACCESS);
		assertEquals(Nfs4.ACCESS4_READ | write, r.in.readInt());
		assertEquals(Nfs4.ACCESS4_READ | write, r.in.readInt());
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

		// a LOCK for a new lock-owner that fails counts as the open-owner's seqid 5 (LOCK16, OPDG11)
		ops = new Ops().putfh(d.fh).op(Nfs4.OP_LOCK);
		ops.args().writeInt(Nfs4.READ_LT).writeBoolean(false).writeLong(0).writeLong(0 /* INVAL */).writeBoolean(true).writeInt(5);
		d.write(ops.args());
		ops.args().writeInt(0).writeLong(id).writeOpaque(bytes("lock-owner"));
		r = c.compound(ops);
		assertEquals(Nfs4.NFS4ERR_INVAL, r.status);
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
	public void theStateFolderGetsANodeFile(@TempDir Path tmp) throws Exception {
		property("test.nfs.state.stateDir", tmp.toString());
		property("test.nfs.state.maxNodes", "50");
		TestClient c = start("test.nfs.state", false);
		assertEquals(Nfs4.NFS4_OK, c.compound(new Ops().putroot().getattr(Nfs4.FATTR4_TYPE)).status);
		try (java.util.stream.Stream<Path> files = Files.list(tmp)) {
			List<String> names = new ArrayList<String>();
			files.forEach(f -> names.add(f.getFileName().toString()));
			assertEquals(2, names.size(), names.toString());
			java.util.Collections.sort(names);
			assertTrue(names.get(0).startsWith("test.") && names.get(0).endsWith(".acls"), names.get(0));
			assertTrue(names.get(1).startsWith("test.") && names.get(1).endsWith(".nodes"), names.get(1));
		}
		assertEquals(50, server.getExports().get(0).getNodes().getMaxNodes());
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

	/** Every setting the server reads is in this module's list, so the start-up check doesn't warn about them. */
	@Test
	public void everySettingTheServerReadsIsListed(@TempDir Path tmp) throws Exception {
		// a name must start with parley. for the check to look at it
		String prefix = "parley.nfs";
		KnownSettings known = KnownSettings.load().addPrefix(prefix);
		Path folder = Files.createDirectory(tmp.resolve("docs"));
		Properties values = new Properties();
		values.setProperty(prefix+".exports", "docs");
		values.setProperty(prefix+".export.docs", folder.toString());
		values.setProperty(prefix+".export.docs.readOnly", "true");
		KnownSettings.RecordingProperties recording = new KnownSettings.RecordingProperties(values);
		java.util.Properties savedProperties = System.getProperties();
		recording.putAll(savedProperties);
		System.setProperties(recording);
		try {
			server = new NfsServer(prefix, 0);
			server.startAndWait(10000);
		} finally {
			System.setProperties(savedProperties);
		}
		List<String> asked = new ArrayList<String>(recording.asked());
		asked.removeIf(n -> !n.startsWith("parley."));
		assertTrue(asked.contains(prefix+".leaseTime"), asked.toString());
		assertTrue(asked.contains(prefix+".export.docs.readOnly"), asked.toString());
		assertTrue(asked.contains(prefix+".hideUnreadableExports"), asked.toString());
		assertEquals(List.of(), known.undeclared(asked));
		assertEquals(List.of(), known.undeclared(KnownSettings.settingConstants(NfsServer.class)));
		// the names a deployment sets
		assertEquals(List.of(), known.undeclared(List.of("parley.nfs.configFile", "parley.nfs.export", "parley.nfs.idmap",
				"parley.nfs.anonymous", "parley.nfs.authenticationProvider", "parley.nfs.userFile")));
		// and a misspelled one is found
		assertEquals(List.of("parley.nfs.exprot"), known.undeclared(List.of("parley.nfs.exprot")));
	}

	/** A refusal is reported once for each fresh answer, not for each call. */
	@Test
	public void refusalsAreReportedOncePerAnswer() throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		List<String> told = new ArrayList<String>();
		Authorizer a = new Authorizer(new BasicUserRegistry(1000), null, null, "test", 60000, told::add);
		Export export = new Export("test", root, false);
		for (int i = 0; i < 3; i++) {
			try {
				a.check(export, Caller.none(), java.net.InetAddress.getLoopbackAddress(), 2049, false);
				org.junit.jupiter.api.Assertions.fail("allowed");
			} catch (us.bringardner.parley.nfs.server.Nfs4Exception e) {
				assertEquals(Nfs4.NFS4ERR_ACCESS, e.getStatus());
			}
		}
		assertEquals(1, told.size(), told.toString());
		assertTrue(told.get(0).startsWith("Refused read on test to AUTH_NONE"), told.get(0));
	}

	// ------------------------------------------------------------------ several exports (D27 to D32)

	private FileSource makeExportRoot(String path) throws IOException {
		FileSource f = mem.createFileSource(path);
		assertTrue(f.mkdirs());
		return f;
	}

	private static void write(FileSource f, int length) throws IOException {
		assertTrue(f.createNewFile());
		try (java.io.OutputStream out = f.getOutputStream()) {
			out.write(new byte[length]);
		}
	}

	private static byte[] fhOf(TestClient c, String... path) throws IOException {
		Ops ops = new Ops().putroot();
		for (String n : path) {
			ops.lookup(n);
		}
		Result r = c.compound(ops.getfh());
		r.ok(Nfs4.OP_PUTROOTFH);
		for (int i = 0; i < path.length; i++) {
			r.ok(Nfs4.OP_LOOKUP);
		}
		r.ok(Nfs4.OP_GETFH);
		return r.in.readOpaque(128);
	}

	private void startExports(String prefix, Export... exports) throws IOException {
		server = new NfsServer(prefix, 0);
		server.setExports(Arrays.asList(exports));
		server.startAndWait(10000);
	}

	/** Decisions D27, D29, D31, D32: a read-only folder of exports, handles that name their export. */
	@Test
	public void exportsAreInAReadOnlyFolder() throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource docs = makeExportRoot("/docs");
		FileSource media = makeExportRoot("/media");
		FileSource archive = makeExportRoot("/archive");
		write(docs.getChild("x"), 10);
		write(media.getChild("x"), 20);
		startExports("test.nfs.multi", new Export("docs", docs, false), new Export("media", media, false),
				new Export("archive", archive, true));
		TestClient c = client();

		byte[] top = rootFh(c);
		assertEquals(Arrays.asList("archive", "docs", "media"), readdir(c, top, 4096));
		byte[] d = fhOf(c, "docs");
		byte[] m = fhOf(c, "media");
		assertEquals(Arrays.asList("x"), readdir(c, d, 4096));

		// the same path in two exports: different handles, each in its own export
		byte[] dx = fhOf(c, "docs", "x");
		byte[] mx = fhOf(c, "media", "x");
		assertFalse(Arrays.equals(dx, mx));
		assertEquals(10, size(c, dx));
		assertEquals(20, size(c, mx));

		// the fsid of an export isn't the folder's
		assertDifferent(fsid(c, top), fsid(c, d));
		assertDifferent(fsid(c, d), fsid(c, m));

		// LOOKUPP: from an export's root to the folder of exports, and no further
		Result r = c.compound(new Ops().putfh(d).op(Nfs4.OP_LOOKUPP).getfh());
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_LOOKUPP).ok(Nfs4.OP_GETFH);
		assertArrayEquals(top, r.in.readOpaque(128));
		assertEquals(Nfs4.NFS4ERR_NOENT, c.compound(new Ops().putfh(top).op(Nfs4.OP_LOOKUPP)).status);
		assertEquals(Nfs4.NFS4ERR_NOENT, c.compound(new Ops().putroot().lookup("nothing")).status);

		// the folder of exports can't be changed, and a read-only export keeps its own flag
		Ops mk = new Ops().putfh(top).op(Nfs4.OP_CREATE);
		mk.args().writeInt(Nfs4.NF4DIR).writeString("sub").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4ERR_ROFS, c.compound(mk).status);
		Ops mk2 = new Ops().putfh(fhOf(c, "archive")).op(Nfs4.OP_CREATE);
		mk2.args().writeInt(Nfs4.NF4DIR).writeString("sub").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4ERR_ROFS, c.compound(mk2).status);
		Ops mk3 = new Ops().putfh(d).op(Nfs4.OP_CREATE);
		mk3.args().writeInt(Nfs4.NF4DIR).writeString("sub").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4_OK, c.compound(mk3).status);
		assertTrue(docs.getChild("sub").isDirectory());

		// RENAME from one export to another is XDEV
		Ops mv = new Ops().putfh(d).op(Nfs4.OP_SAVEFH).putfh(m).op(Nfs4.OP_RENAME);
		mv.args().writeString("x").writeString("moved");
		assertEquals(Nfs4.NFS4ERR_XDEV, c.compound(mv).status);
		assertTrue(docs.getChild("x").exists());

		// a handle that names no export is stale, and a short one is bad
		byte[] other = dx.clone();
		other[1] ^= 0x55;
		assertEquals(Nfs4.NFS4ERR_STALE, c.compound(new Ops().putfh(other).getattr(Nfs4.FATTR4_SIZE)).status);
		assertEquals(Nfs4.NFS4ERR_BADHANDLE, c.compound(new Ops().putfh(new byte[5])).status);
	}

	private static long[] fsid(TestClient c, byte[] fh) throws IOException {
		Result r = c.compound(new Ops().putfh(fh).getattr(Nfs4.FATTR4_FSID));
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_GETATTR);
		r.in.readIntArray(8);
		r.in.readInt();
		return new long[] { r.in.readLong(), r.in.readLong() };
	}

	private static void assertDifferent(long[] a, long[] b) {
		assertFalse(Arrays.equals(a, b), Arrays.toString(a));
	}

	/** Decisions D29, D30: each export is authorized by itself; the folder hides what a caller can't read, or lists it. */
	@Test
	public void unreadableExportsAreHiddenOrListed(@TempDir Path tmp) throws Exception {
		Path idmap = tmp.resolve("idmap.properties");
		Files.write(idmap, "uid.1000=tony\n".getBytes(StandardCharsets.UTF_8));
		property("test.nfs.hide.idmap", idmap.toString());
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		Export docs = new Export("docs", makeExportRoot("/docs"), false);
		Export media = new Export("media", makeExportRoot("/media"), false);
		BasicUserRegistry registry = new BasicUserRegistry(1000);
		ParleyPrincipal tony = registry.createUser("tony");
		registry.grant(tony, new BasicParleyPermission(StandardPermissions.READ, docs.getId()));
		server = new NfsServer("test.nfs.hide", 0);
		server.setExports(Arrays.asList(docs, media));
		server.setUserRegistry(registry);
		server.startAndWait(10000);

		TestClient c = client();
		c.uid = 1000;
		byte[] top = rootFh(c);
		assertEquals(Arrays.asList("docs"), readdir(c, top, 4096));
		assertEquals(Nfs4.NFS4_OK, c.compound(new Ops().putroot().lookup("docs")).status);
		assertEquals(Nfs4.NFS4ERR_ACCESS, c.compound(new Ops().putroot().lookup("media")).status);
		assertEquals(Nfs4.NFS4ERR_NOENT, c.compound(new Ops().putroot().lookup("nothing")).status);

		// listed all: the setting, read when the server starts
		server.stop();
		assertTrue(server.awaitStopped(10000));
		property("test.nfs.hide.hideUnreadableExports", "false");
		server.startAndWait(10000);
		TestClient t = client();
		t.uid = 1000;
		assertEquals(Arrays.asList("docs", "media"), readdir(t, rootFh(t), 4096));
		assertEquals(Nfs4.NFS4ERR_ACCESS, t.compound(new Ops().putroot().lookup("media")).status);
	}

	/** Decision D28: the settings name the exports; the old one-folder setting stays as it was. */
	@Test
	public void exportsFromSettings(@TempDir Path tmp) throws Exception {
		Path a = Files.createDirectory(tmp.resolve("a"));
		Path b = Files.createDirectory(tmp.resolve("b"));
		Files.write(a.resolve("in-a"), new byte[3]);
		property("test.nfs.set.exports", "docs, media");
		property("test.nfs.set.export.docs", a.toString());
		property("test.nfs.set.export.media", b.toString());
		property("test.nfs.set.export.media.readOnly", "true");
		server = new NfsServer("test.nfs.set", 0);
		server.startAndWait(10000);
		assertEquals(2, server.getExports().size());
		assertTrue(server.findExport("media").isReadOnly());
		assertFalse(server.findExport("docs").isReadOnly());
		TestClient c = client();
		assertEquals(Arrays.asList("docs", "media"), readdir(c, rootFh(c), 4096));
		assertEquals(Arrays.asList("in-a"), readdir(c, fhOf(c, "docs"), 4096));
		server.stop();
		assertTrue(server.awaitStopped(10000));

		// one folder and a list of names is a mistake
		property("test.nfs.set.export", a.toString());
		NfsServer both = new NfsServer("test.nfs.set", 0);
		try {
			both.startAndWait(5000);
			org.junit.jupiter.api.Assertions.fail("started");
		} catch (IOException e) {
			assertTrue(e.getMessage().contains("both set"), e.getMessage());
		}
		System.clearProperty("test.nfs.set.export");
		// a name with no folder
		System.clearProperty("test.nfs.set.export.media");
		NfsServer missing = new NfsServer("test.nfs.set", 0);
		try {
			missing.startAndWait(5000);
			org.junit.jupiter.api.Assertions.fail("started");
		} catch (IOException e) {
			assertTrue(e.getMessage().contains("test.nfs.set.export.media"), e.getMessage());
		}
	}

	/** Two exports can't have the same name. */
	@Test
	public void exportNamesAreUnique() throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		NfsServer s = new NfsServer("test.nfs.dup", 0);
		s.setExports(Arrays.asList(new Export("docs", makeExportRoot("/one"), false), new Export("docs", makeExportRoot("/two"), false)));
		try {
			s.startAndWait(5000);
			org.junit.jupiter.api.Assertions.fail("started");
		} catch (IOException e) {
			assertTrue(e.getMessage().contains("two exports named docs"), e.getMessage());
		}
	}

	/** Decision D75: a local file's owner and group, when no registry or id map names them, are the operating system's uid and gid. */
	@Test
	public void aLocalFileShowsItsOperatingSystemIds(@TempDir Path tmp) throws Exception {
		Path file = tmp.resolve("f");
		Files.write(file, bytes("x"));
		int uid;
		int gid;
		try {
			uid = (Integer) Files.getAttribute(file, "unix:uid");
			gid = (Integer) Files.getAttribute(file, "unix:gid");
		} catch (UnsupportedOperationException | IllegalArgumentException e) {
			org.junit.jupiter.api.Assumptions.assumeTrue(false, "no unix view here: " + e);
			return;
		}
		root = new us.bringardner.parley.files.fileproxy.FileProxyFactory().createFileSource(tmp.toString());
		server = new NfsServer("test.nfs.local-ids", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		TestClient c = client();
		clientid(c, "client-ids");
		Result r = c.compound(new Ops().putroot().lookup("f").getattr(Nfs4.FATTR4_OWNER, Nfs4.FATTR4_OWNER_GROUP));
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_LOOKUP).ok(Nfs4.OP_GETATTR);
		r.in.readIntArray(8);
		r.in.readInt();
		assertEquals(Long.toString(uid & 0xFFFFFFFFL), r.in.readString(1024), "the owner as the operating system's uid");
		assertEquals(Long.toString(gid & 0xFFFFFFFFL), r.in.readString(1024), "the group as its gid");
	}

	private static String owner(TestClient c, byte[] fh) throws IOException {
		Result r = c.compound(new Ops().putfh(fh).getattr(Nfs4.FATTR4_OWNER));
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_GETATTR);
		r.in.readIntArray(8);
		r.in.readInt();
		return r.in.readString(1024);
	}

	private static int setOwner(TestClient c, byte[] fh, String owner) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_SETATTR);
		XdrEncoder vals = new XdrEncoder().writeString(owner);
		ops.args().writeInt(0).writeFixedOpaque(new byte[12])
			.writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_OWNER)).writeOpaque(vals.toByteArray());
		return c.compound(ops).status;
	}

	/** Decision D15: an owner in the id map is sent as its uid number. */
	@Test
	public void ownersInTheIdMapAreUidNumbers(@TempDir Path tmp) throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		String name = root.getOwner().getName();
		Path idmap = tmp.resolve("idmap.properties");
		Files.write(idmap, ("uid.1000="+name+"\nuid.1500="+name+"\nuid.1001=pat\n").getBytes(StandardCharsets.UTF_8));
		property("test.nfs.owner.idmap", idmap.toString());
		property("test.nfs.owner.domain", "example.org");
		server = new NfsServer("test.nfs.owner", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		TestClient c = client();
		byte[] fh = c.compound(new Ops().putroot().getfh()).ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_GETFH).in.readOpaque(128);

		// the lowest uid the name is mapped from
		assertEquals("1000", owner(c, fh));
		// the owner as it is (a number or the name) isn't a change; another is NFS4ERR_PERM
		assertEquals(Nfs4.NFS4_OK, setOwner(c, fh, "1000"));
		assertEquals(Nfs4.NFS4_OK, setOwner(c, fh, "1500"));
		assertEquals(Nfs4.NFS4_OK, setOwner(c, fh, name+"@example.org"));
		assertEquals(Nfs4.NFS4ERR_PERM, setOwner(c, fh, "1001"));
		assertEquals(Nfs4.NFS4ERR_PERM, setOwner(c, fh, "2000"));
	}

	@Test
	public void ownersNotInTheIdMapAreNames() throws Exception {
		property("test.nfs.owner2.domain", "example.org");
		TestClient c = start("test.nfs.owner2", false);
		byte[] fh = c.compound(new Ops().putroot().getfh()).ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_GETFH).in.readOpaque(128);
		assertEquals(root.getOwner().getName()+"@example.org", owner(c, fh));
		assertEquals(Nfs4.NFS4ERR_PERM, setOwner(c, fh, "1000"));
	}

	private static int setMode(TestClient c, byte[] fh, int mode) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_SETATTR);
		ops.args().writeInt(0).writeFixedOpaque(new byte[12])
			.writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_MODE))
			.writeOpaque(new XdrEncoder().writeInt(mode).toByteArray());
		return c.compound(ops).status;
	}

	/**
	 * Decision D16: an OPEN for writing may write whatever mode the file gets after it, as git
	 * does (O_CREAT|O_EXCL with mode 0444, then write). Local files, where a mode without write
	 * stops a new write stream (unless the test runs as root).
	 */
	@Test
	public void openForWritingWritesAReadOnlyFile(@TempDir Path tmp) throws Exception {
		root = new us.bringardner.parley.files.fileproxy.FileProxyFactory().createFileSource(tmp.toString());
		server = new NfsServer("test.nfs.ro-mode", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		TestClient c = client();
		long id = clientid(c, "client-ro-mode");

		// the Linux client's way: OPEN (EXCLUSIVE4), SETATTR mode 0444, then WRITE
		Opened o = open(c, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, Nfs4.OPEN4_SHARE_DENY_NONE, id, "git", true, "obj1");
		assertEquals(Nfs4.NFS4_OK, o.status);
		confirm(c, o, 2);
		assertEquals(Nfs4.NFS4_OK, setMode(c, o.fh, 0444));
		assertEquals(Nfs4.NFS4_OK, write(c, o.fh, o, 0, bytes("object one"), Nfs4.UNSTABLE4));
		assertEquals(Nfs4.NFS4_OK, close(c, o.fh, o, 3));
		assertArrayEquals(bytes("object one"), Files.readAllBytes(tmp.resolve("obj1")));

		// the mode given with OPEN (UNCHECKED4)
		XdrEncoder args = new XdrEncoder();
		args.writeInt(4).writeInt(Nfs4.OPEN4_SHARE_ACCESS_WRITE).writeInt(Nfs4.OPEN4_SHARE_DENY_NONE).writeLong(id)
			.writeOpaque(bytes("git")).writeInt(Nfs4.OPEN4_CREATE).writeInt(Nfs4.UNCHECKED4)
			.writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_MODE))
			.writeOpaque(new XdrEncoder().writeInt(0444).toByteArray()).writeInt(Nfs4.CLAIM_NULL).writeString("obj2");
		Ops ops = new Ops().putroot().op(Nfs4.OP_OPEN);
		ops.args().write(args);
		ops.getfh();
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_OPEN);
		Opened o2 = new Opened();
		o2.seqid = r.in.readInt();
		o2.other = r.in.readFixedOpaque(12);
		r.in.readBoolean();
		r.in.readLong();
		r.in.readLong();
		r.in.readInt();
		r.in.readIntArray(8);
		r.in.readInt();
		r.ok(Nfs4.OP_GETFH);
		o2.fh = r.in.readOpaque(128);
		assertEquals(Nfs4.NFS4_OK, write(c, o2.fh, o2, 0, bytes("object two"), Nfs4.FILE_SYNC4));
		assertEquals(Nfs4.NFS4_OK, close(c, o2.fh, o2, 5));
		assertArrayEquals(bytes("object two"), Files.readAllBytes(tmp.resolve("obj2")));

		// git on a Linux client exactly: O_CREAT|O_EXCL is OPEN EXCLUSIVE4, then SETATTR with the
		// open's stateid sets the mode and the times (the verifier took them), then WRITE, COMMIT
		args = new XdrEncoder();
		args.writeInt(6).writeInt(Nfs4.OPEN4_SHARE_ACCESS_BOTH).writeInt(Nfs4.OPEN4_SHARE_DENY_NONE).writeLong(id)
			.writeOpaque(bytes("git")).writeInt(Nfs4.OPEN4_CREATE).writeInt(Nfs4.EXCLUSIVE4)
			.writeFixedOpaque(bytes("verifier")).writeInt(Nfs4.CLAIM_NULL).writeString("obj3");
		ops = new Ops().putroot().op(Nfs4.OP_OPEN);
		ops.args().write(args);
		ops.getfh();
		r = c.compound(ops);
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_OPEN);
		Opened o3 = new Opened();
		o3.seqid = r.in.readInt();
		o3.other = r.in.readFixedOpaque(12);
		r.in.readBoolean();
		r.in.readLong();
		r.in.readLong();
		r.in.readInt();
		r.in.readIntArray(8);
		r.in.readInt();
		r.ok(Nfs4.OP_GETFH);
		o3.fh = r.in.readOpaque(128);
		ops = new Ops().putfh(o3.fh).op(Nfs4.OP_SETATTR);
		o3.write(ops.args());
		ops.args().writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_MODE,
				Nfs4.FATTR4_TIME_ACCESS_SET, Nfs4.FATTR4_TIME_MODIFY_SET))
			.writeOpaque(new XdrEncoder().writeInt(0444).writeInt(0).writeInt(0).toByteArray());
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		assertEquals(Nfs4.NFS4_OK, write(c, o3.fh, o3, 0, bytes("object three"), Nfs4.UNSTABLE4));
		ops = new Ops().putfh(o3.fh).op(Nfs4.OP_COMMIT);
		ops.args().writeLong(0).writeInt(0);
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
		assertEquals(Nfs4.NFS4_OK, close(c, o3.fh, o3, 7));
		assertArrayEquals(bytes("object three"), Files.readAllBytes(tmp.resolve("obj3")));
	}

	/** A LOCK or LOCKT result: the lock stateid, or the lock that denied it. */
	private static final class Locked {
		int status;
		Opened lock;
		long offset;
		long length;
		int type;
		long clientid;
		String owner;
	}

	private static Locked lockResult(Result r, int op) throws IOException {
		Locked ret = new Locked();
		ret.status = r.next(op);
		if( ret.status == Nfs4.NFS4_OK && op == Nfs4.OP_LOCK ) {
			ret.lock = new Opened();
			ret.lock.seqid = r.in.readInt();
			ret.lock.other = r.in.readFixedOpaque(12);
		} else if( ret.status == Nfs4.NFS4ERR_DENIED ) {
			ret.offset = r.in.readLong();
			ret.length = r.in.readLong();
			ret.type = r.in.readInt();
			ret.clientid = r.in.readLong();
			ret.owner = new String(r.in.readOpaque(1024), StandardCharsets.UTF_8);
		}
		return ret;
	}

	/** LOCK for a new lock-owner (open_to_lock_owner4) */
	private static Locked lockNew(TestClient c, byte[] fh, Opened open, int openSeqid, long clientid, String owner,
			int type, long offset, long length) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_LOCK);
		ops.args().writeInt(type).writeBoolean(false).writeLong(offset).writeLong(length).writeBoolean(true).writeInt(openSeqid);
		open.write(ops.args());
		ops.args().writeInt(0).writeLong(clientid).writeOpaque(bytes(owner));
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		return lockResult(r, Nfs4.OP_LOCK);
	}

	/** LOCK of a lock-owner that has a lock stateid (exist_lock_owner4) */
	private static Ops lockMoreOps(byte[] fh, Opened lock, int lockSeqid, int type, long offset, long length) {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_LOCK);
		ops.args().writeInt(type).writeBoolean(false).writeLong(offset).writeLong(length).writeBoolean(false);
		lock.write(ops.args());
		ops.args().writeInt(lockSeqid);
		return ops;
	}

	private static Locked lockMore(TestClient c, byte[] fh, Opened lock, int lockSeqid, int type, long offset, long length)
			throws IOException {
		Result r = c.compound(lockMoreOps(fh, lock, lockSeqid, type, offset, length));
		r.ok(Nfs4.OP_PUTFH);
		Locked ret = lockResult(r, Nfs4.OP_LOCK);
		if( ret.lock != null ) {
			lock.seqid = ret.lock.seqid;
		}
		return ret;
	}

	private static int locku(TestClient c, byte[] fh, Opened lock, int lockSeqid, long offset, long length) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_LOCKU);
		ops.args().writeInt(Nfs4.WRITE_LT).writeInt(lockSeqid);
		lock.write(ops.args());
		ops.args().writeLong(offset).writeLong(length);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		int status = r.next(Nfs4.OP_LOCKU);
		if( status == Nfs4.NFS4_OK ) {
			lock.seqid = r.in.readInt();
			r.in.readFixedOpaque(12);
		}
		return status;
	}

	private static Locked lockt(TestClient c, byte[] fh, long clientid, String owner, int type, long offset, long length)
			throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_LOCKT);
		ops.args().writeInt(type).writeLong(offset).writeLong(length).writeLong(clientid).writeOpaque(bytes(owner));
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		return lockResult(r, Nfs4.OP_LOCKT);
	}

	private static int releaseLockOwner(TestClient c, long clientid, String owner) throws IOException {
		Ops ops = new Ops().op(Nfs4.OP_RELEASE_LOCKOWNER);
		ops.args().writeLong(clientid).writeOpaque(bytes(owner));
		return c.compound(ops).status;
	}

	/** Decisions D17 to D21: byte-range locks between two clients. */
	@Test
	public void byteRangeLocks() throws Exception {
		TestClient a = start("test.nfs.locks", false);
		TestClient b = client();
		long ida = clientid(a, "client-a");
		long idb = clientid(b, "client-b");
		Opened oa = open(a, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, Nfs4.OPEN4_SHARE_DENY_NONE, ida, "a", true, "f");
		confirm(a, oa, 2);
		Opened ob = open(b, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, Nfs4.OPEN4_SHARE_DENY_NONE, idb, "b", false, "f");
		confirm(b, ob, 2);
		byte[] fh = oa.fh;

		// A write-locks [0, 100); the lock-owner's seqids start at 0 (lock_seqid), the stateid's at 1
		Locked la = lockNew(a, fh, oa, 3, ida, "a-lock", Nfs4.WRITE_LT, 0, 100);
		assertEquals(Nfs4.NFS4_OK, la.status);
		assertEquals(1, la.lock.seqid);
		Opened lockA = la.lock;

		// B can't read-lock inside it: denied with A's lock
		Locked lb = lockNew(b, fh, ob, 3, idb, "b-lock", Nfs4.READ_LT, 50, 10);
		assertEquals(Nfs4.NFS4ERR_DENIED, lb.status);
		assertEquals(0, lb.offset);
		assertEquals(100, lb.length);
		assertEquals(Nfs4.WRITE_LT, lb.type);
		assertEquals(ida, lb.clientid);
		assertEquals("a-lock", lb.owner);

		// LOCKT
		assertEquals(Nfs4.NFS4_OK, lockt(b, fh, idb, "b-lock", Nfs4.WRITE_LT, 100, 100).status);
		assertEquals(Nfs4.NFS4ERR_DENIED, lockt(b, fh, idb, "b-lock", Nfs4.READ_LT, 99, 1).status);
		// the owner's own locks don't conflict
		assertEquals(Nfs4.NFS4_OK, lockt(a, fh, ida, "a-lock", Nfs4.WRITE_LT, 0, 100).status);

		// unlocking the middle splits the lock: [0, 40) and [60, 100)
		assertEquals(Nfs4.NFS4_OK, locku(a, fh, lockA, 1, 40, 20));
		assertEquals(Nfs4.NFS4_OK, lockt(b, fh, idb, "b-lock", Nfs4.WRITE_LT, 45, 10).status);
		Locked t = lockt(b, fh, idb, "b-lock", Nfs4.READ_LT, 30, 5);
		assertEquals(Nfs4.NFS4ERR_DENIED, t.status);
		assertEquals(0, t.offset);
		assertEquals(40, t.length);
		t = lockt(b, fh, idb, "b-lock", Nfs4.READ_LT, 70, 5);
		assertEquals(60, t.offset);
		assertEquals(40, t.length);

		// downgrade [0, 40) to a read lock: B may read-lock there, not write-lock
		assertEquals(Nfs4.NFS4_OK, lockMore(a, fh, lockA, 2, Nfs4.READ_LT, 0, 40).status);
		lb = lockNew(b, fh, ob, 4, idb, "b-lock", Nfs4.READ_LT, 10, 10);
		assertEquals(Nfs4.NFS4_OK, lb.status);
		Opened lockB = lb.lock;
		Locked denied = lockMore(b, fh, lockB, 1, Nfs4.WRITE_LT, 10, 10);
		assertEquals(Nfs4.NFS4ERR_DENIED, denied.status);
		assertEquals(Nfs4.READ_LT, denied.type);
		// a retransmission of the denied LOCK is denied the same way
		Result again = b.compoundAgain(lockMoreOps(fh, lockB, 1, Nfs4.WRITE_LT, 10, 10));
		again.ok(Nfs4.OP_PUTFH);
		Locked replayed = lockResult(again, Nfs4.OP_LOCK);
		assertEquals(Nfs4.NFS4ERR_DENIED, replayed.status);
		assertEquals(denied.offset, replayed.offset);
		assertEquals("a-lock", replayed.owner);

		// a lock to the end of the file (length all ones)
		assertEquals(Nfs4.NFS4_OK, lockMore(a, fh, lockA, 3, Nfs4.WRITE_LT, 1000, -1L).status);
		t = lockt(b, fh, idb, "b-lock", Nfs4.READ_LT, 1L << 40, 1);
		assertEquals(Nfs4.NFS4ERR_DENIED, t.status);
		assertEquals(1000, t.offset);
		assertEquals(-1L, t.length);

		// WRITE with the lock stateid
		assertEquals(Nfs4.NFS4_OK, write(a, fh, lockA, 0, bytes("locked"), Nfs4.FILE_SYNC4));

		// bad ranges and types
		assertEquals(Nfs4.NFS4ERR_INVAL, lockMore(a, fh, lockA, 4, Nfs4.READ_LT, 0, 0).status);
		assertEquals(Nfs4.NFS4ERR_INVAL, lockMore(a, fh, lockA, 5, Nfs4.READ_LT, -2L, 5).status);
		assertEquals(Nfs4.NFS4ERR_INVAL, lockMore(a, fh, lockA, 6, 9, 0, 5).status);

		// locks held: CLOSE and RELEASE_LOCKOWNER are refused (D20)
		assertEquals(Nfs4.NFS4ERR_LOCKS_HELD, close(a, fh, oa, 4));
		assertEquals(Nfs4.NFS4ERR_LOCKS_HELD, releaseLockOwner(a, ida, "a-lock"));
		// unlock everything: both work
		assertEquals(Nfs4.NFS4_OK, locku(a, fh, lockA, 7, 0, -1L));
		assertEquals(Nfs4.NFS4_OK, lockt(b, fh, idb, "b-lock", Nfs4.WRITE_LT, 0, -1L).status);
		assertEquals(Nfs4.NFS4_OK, releaseLockOwner(a, ida, "a-lock"));
		assertEquals(Nfs4.NFS4_OK, close(a, fh, oa, 5));
		// the released lock stateid is gone
		assertEquals(Nfs4.NFS4ERR_BAD_STATEID, lockMore(a, fh, lockA, 8, Nfs4.READ_LT, 0, 1).status);
	}

	@Test
	public void lockErrors() throws Exception {
		TestClient a = start("test.nfs.lockerr", false);
		long ida = clientid(a, "client-err");
		Opened ro = open(a, 1, Nfs4.OPEN4_SHARE_ACCESS_READ, Nfs4.OPEN4_SHARE_DENY_NONE, ida, "a", true, "f");
		confirm(a, ro, 2);
		// a write lock needs write access
		assertEquals(Nfs4.NFS4ERR_OPENMODE, lockNew(a, ro.fh, ro, 3, ida, "l", Nfs4.WRITE_LT, 0, 10).status);
		// no grace period (D21)
		Ops ops = new Ops().putfh(ro.fh).op(Nfs4.OP_LOCK);
		ops.args().writeInt(Nfs4.READ_LT).writeBoolean(true).writeLong(0).writeLong(10).writeBoolean(true).writeInt(4);
		ro.write(ops.args());
		ops.args().writeInt(0).writeLong(ida).writeOpaque(bytes("l"));
		assertEquals(Nfs4.NFS4ERR_NO_GRACE, a.compound(ops).status);
		// both failures counted as the open-owner's seqids: this is 5
		Locked l = lockNew(a, ro.fh, ro, 5, ida, "l", Nfs4.READW_LT, 0, 10);
		assertEquals(Nfs4.NFS4_OK, l.status);
		assertEquals(1, server.getStateManager().getLockCount());
	}

	// ------------------------------------------------------------------ mirrored locks (D68)

	/** @return true if this JVM holds a lock on the region of the file (the server's mirror, here) */
	private boolean fileLocked(String name, long position, long size) throws IOException {
		try (FileSourceLock l = root.getChild(name).tryLock(position, size, false)) {
			return l == null;
		} catch (OverlappingFileLockException e) {
			return true;
		}
	}

	/** A client's locks are file locks too: held while it holds them, split and joined as they are. */
	@Test
	public void lockedRangesAreHeldAsFileLocks() throws Exception {
		property("test.nfs.mirror.mirrorLocks", "true");
		TestClient a = start("test.nfs.mirror", false);
		long ida = clientid(a, "client-a");
		Opened oa = open(a, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, Nfs4.OPEN4_SHARE_DENY_NONE, ida, "a", true, "f");
		confirm(a, oa, 2);
		assertFalse(fileLocked("f", 0, 100));

		Locked la = lockNew(a, oa.fh, oa, 3, ida, "a-lock", Nfs4.WRITE_LT, 0, 50);
		assertEquals(Nfs4.NFS4_OK, la.status);
		assertEquals(Nfs4.NFS4_OK, lockMore(a, oa.fh, la.lock, 1, Nfs4.WRITE_LT, 50, 50).status);
		assertTrue(fileLocked("f", 10, 1));
		assertTrue(fileLocked("f", 90, 1));
		assertFalse(fileLocked("f", 100, 10));

		// a part unlocked: the rest is held, the part is not
		assertEquals(Nfs4.NFS4_OK, locku(a, oa.fh, la.lock, 2, 40, 20));
		assertTrue(fileLocked("f", 10, 1));
		assertFalse(fileLocked("f", 45, 10));
		assertTrue(fileLocked("f", 90, 1));

		// to the end of the file
		assertEquals(Nfs4.NFS4_OK, lockMore(a, oa.fh, la.lock, 3, Nfs4.READ_LT, 1000, -1L).status);
		assertTrue(fileLocked("f", 1L << 40, 1));

		assertEquals(Nfs4.NFS4_OK, locku(a, oa.fh, la.lock, 4, 0, -1L));
		assertFalse(fileLocked("f", 0, 100));
		assertFalse(fileLocked("f", 1000, 100));
	}

	/** Another program's lock on the file is the answer to a LOCK or a LOCKT, and nothing is kept. */
	@Test
	public void aLockAnotherProgramHoldsDeniesTheLock() throws Exception {
		property("test.nfs.mirrorforeign.mirrorLocks", "true");
		TestClient a = start("test.nfs.mirrorforeign", false);
		long ida = clientid(a, "client-a");
		Opened oa = open(a, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, Nfs4.OPEN4_SHARE_DENY_NONE, ida, "a", true, "f");
		confirm(a, oa, 2);
		try (FileSourceLock foreign = root.getChild("f").tryLock(0, 100, false)) {
			assertNotNull(foreign);
			Locked l = lockNew(a, oa.fh, oa, 3, ida, "a-lock", Nfs4.WRITE_LT, 50, 10);
			assertEquals(Nfs4.NFS4ERR_DENIED, l.status);
			assertEquals(50, l.offset);
			assertEquals(10, l.length);
			assertEquals(Nfs4.WRITE_LT, l.type);
			assertEquals(0, l.clientid);
			assertEquals("", l.owner);
			assertEquals(0, server.getStateManager().getLockCount());

			Locked t = lockt(a, oa.fh, ida, "a-lock", Nfs4.READ_LT, 99, 5);
			assertEquals(Nfs4.NFS4ERR_DENIED, t.status);
			assertEquals(99, t.offset);
			// past it
			assertEquals(Nfs4.NFS4_OK, lockt(a, oa.fh, ida, "a-lock", Nfs4.WRITE_LT, 100, 5).status);
		}
		// released: the lock is given, and the foreign lock is not there to test
		Locked l = lockNew(a, oa.fh, oa, 4, ida, "a-lock", Nfs4.WRITE_LT, 50, 10);
		assertEquals(Nfs4.NFS4_OK, l.status);
		assertTrue(fileLocked("f", 55, 1));
		assertEquals(Nfs4.NFS4_OK, lockt(a, oa.fh, ida, "a-lock", Nfs4.WRITE_LT, 50, 10).status);
	}

	/** A LOCK that can't be given leaves what the owner held as it was, still held. */
	@Test
	public void aRefusedLockKeepsWhatTheOwnerHeld() throws Exception {
		property("test.nfs.mirrorkeep.mirrorLocks", "true");
		TestClient a = start("test.nfs.mirrorkeep", false);
		long ida = clientid(a, "client-a");
		Opened oa = open(a, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, Nfs4.OPEN4_SHARE_DENY_NONE, ida, "a", true, "f");
		confirm(a, oa, 2);
		Locked la = lockNew(a, oa.fh, oa, 3, ida, "a-lock", Nfs4.WRITE_LT, 0, 100);
		assertEquals(Nfs4.NFS4_OK, la.status);
		try (FileSourceLock foreign = root.getChild("f").tryLock(150, 10, false)) {
			assertNotNull(foreign);
			// reaching over the foreign lock
			assertEquals(Nfs4.NFS4ERR_DENIED, lockMore(a, oa.fh, la.lock, 1, Nfs4.WRITE_LT, 0, 200).status);
			assertTrue(fileLocked("f", 10, 1));
			assertTrue(fileLocked("f", 99, 1));
			assertFalse(fileLocked("f", 100, 20));
		}
		// the client's lock is still the one of [0, 100)
		assertEquals(Nfs4.NFS4_OK, lockMore(a, oa.fh, la.lock, 2, Nfs4.WRITE_LT, 0, 200).status);
		assertTrue(fileLocked("f", 150, 1));
	}

	/** Without parley.nfs.mirrorLocks the file is not locked, and another program's lock is not looked at. */
	@Test
	public void locksAreNotMirroredByDefault() throws Exception {
		TestClient a = start("test.nfs.nomirror", false);
		long ida = clientid(a, "client-a");
		Opened oa = open(a, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, Nfs4.OPEN4_SHARE_DENY_NONE, ida, "a", true, "f");
		confirm(a, oa, 2);
		assertEquals(Nfs4.NFS4_OK, lockNew(a, oa.fh, oa, 3, ida, "a-lock", Nfs4.WRITE_LT, 0, 100).status);
		assertFalse(fileLocked("f", 0, 100));
		try (FileSourceLock foreign = root.getChild("f").tryLock(200, 10, false)) {
			assertNotNull(foreign);
			assertEquals(Nfs4.NFS4_OK, lockt(a, oa.fh, ida, "a-lock", Nfs4.WRITE_LT, 200, 10).status);
		}
	}

	/** The locks of a client whose lease ran out, and all of them when the server stops, are released. */
	@Test
	public void mirroredLocksEndWithTheLeaseAndTheServer() throws Exception {
		property("test.nfs.mirrorend.mirrorLocks", "true");
		property("test.nfs.mirrorend.leaseTime", "1s");
		TestClient a = start("test.nfs.mirrorend", false);
		long ida = clientid(a, "client-gone");
		Opened oa = open(a, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, Nfs4.OPEN4_SHARE_DENY_NONE, ida, "a", true, "f");
		confirm(a, oa, 2);
		assertEquals(Nfs4.NFS4_OK, lockNew(a, oa.fh, oa, 3, ida, "l", Nfs4.WRITE_LT, 0, -1L).status);
		assertTrue(fileLocked("f", 0, 10));
		long end = System.currentTimeMillis()+10000;
		while( server.getStateManager().getLockCount() > 0 && System.currentTimeMillis() < end ) {
			Thread.sleep(100);
		}
		assertEquals(0, server.getStateManager().getLockCount());
		assertFalse(fileLocked("f", 0, 10));

		// a second client's lock, held while the server stops
		TestClient b = client();
		long idb = clientid(b, "client-b");
		Opened ob = open(b, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, Nfs4.OPEN4_SHARE_DENY_NONE, idb, "b", false, "f");
		confirm(b, ob, 2);
		assertEquals(Nfs4.NFS4_OK, lockNew(b, ob.fh, ob, 3, idb, "l", Nfs4.WRITE_LT, 0, 10).status);
		assertTrue(fileLocked("f", 0, 10));
		server.stop();
		assertTrue(server.awaitStopped(10000));
		assertFalse(fileLocked("f", 0, 10));
	}

	/** D20: a client's locks go when its lease runs out. */
	@Test
	public void locksExpireWithTheLease() throws Exception {
		property("test.nfs.lockexp.leaseTime", "1s");
		TestClient a = start("test.nfs.lockexp", false);
		long ida = clientid(a, "client-gone");
		Opened oa = open(a, 1, Nfs4.OPEN4_SHARE_ACCESS_BOTH, Nfs4.OPEN4_SHARE_DENY_NONE, ida, "a", true, "f");
		confirm(a, oa, 2);
		assertEquals(Nfs4.NFS4_OK, lockNew(a, oa.fh, oa, 3, ida, "l", Nfs4.WRITE_LT, 0, -1L).status);
		assertEquals(1, server.getStateManager().getLockCount());
		long end = System.currentTimeMillis()+10000;
		while( server.getStateManager().getLockCount() > 0 && System.currentTimeMillis() < end ) {
			Thread.sleep(100);
		}
		assertEquals(0, server.getStateManager().getLockCount());
		TestClient b = client();
		long idb = clientid(b, "client-next");
		assertEquals(Nfs4.NFS4_OK, lockt(b, oa.fh, idb, "x", Nfs4.WRITE_LT, 0, -1L).status);
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
