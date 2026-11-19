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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceUser;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.permission.BasicParleyPermission;
import us.bringardner.parley.iam.permission.StandardPermissions;
import us.bringardner.parley.iam.registry.BasicUserRegistry;
import us.bringardner.parley.nfs.TestClient.Ops;
import us.bringardner.parley.nfs.TestClient.Result;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.XdrEncoder;
import us.bringardner.parley.nfs.server.Ace;
import us.bringardner.parley.nfs.server.Acls;
import us.bringardner.parley.nfs.server.Attributes;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/**
 * Access control lists (decisions D51 to D54) over TCP with {@link TestClient}: the acl attribute,
 * checks on each operation, inheritance, and keeping the lists in the state folder.
 */
public class AclNfsTest {

	private static final int TONY = 1000;
	private static final int PAT = 1001;
	private static final int BOB = 1002;
	private static final int CAROL = 1003;

	private static int ownerCount;

	private static final int READ = Nfs4.ACE4_READ_DATA;
	private static final int WRITE = Nfs4.ACE4_WRITE_DATA | Nfs4.ACE4_APPEND_DATA;
	private static final int EXEC = Nfs4.ACE4_EXECUTE;
	private static final int ATTRS = Nfs4.ACE4_READ_ATTRIBUTES | Nfs4.ACE4_READ_ACL | Nfs4.ACE4_SYNCHRONIZE;

	private MemoryFileSourceFactory mem;
	private FileSource root;
	private Export export;
	private BasicUserRegistry registry;
	private NfsServer server;
	private final List<TestClient> clients = new ArrayList<TestClient>();
	private final List<String> properties = new ArrayList<String>();
	/** what the server said at INFO */
	private final List<String> infos = new java.util.concurrent.CopyOnWriteArrayList<String>();

	/** The registry user that uid 0 is mapped to, null to leave root unmapped. */
	private String rootName;
	/** A local folder to export instead of the memory file system. */
	private Path localFolder;

	private void property(String name, String value) {
		System.setProperty(name, value);
		properties.add(name);
	}

	/** tony, pat (in staff), bob and carol (with uid attribute 3000) may read and write the export. */
	private void start(Path tmp, boolean withStateFolder) throws Exception {
		Path idmap = tmp.resolve("idmap.properties");
		Files.write(idmap, ("uid."+TONY+"=tony\nuid."+PAT+"=pat\nuid."+BOB+"=bob\nuid."+CAROL+"=carol\nuid.1004=eve\n"
				+(rootName == null ? "" : "uid.0="+rootName+"\n"))
				.getBytes(StandardCharsets.UTF_8));
		property("test.acl.idmap", idmap.toString());
		property("test.acl.domain", "example.org");
		if( withStateFolder ) {
			property("test.acl.stateDir", tmp.resolve("state").toString());
		}
		if( mem == null ) {
			mem = new MemoryFileSourceFactory();
			mem.connect(new Properties());
			root = localFolder == null ? mem.createFileSource("/export")
					: us.bringardner.parley.files.FileSourceFactory.getDefaultFactory().createFileSource(localFolder.toString());
			assertTrue(root.mkdirs() || root.exists());
			export = new Export("test", root, false);
			registry = new BasicUserRegistry(1000);
			ParleyPrincipal staff = registry.createGroup("staff");
			for (String name : rootName == null ? new String[] {"tony", "pat", "bob", "carol"} : new String[] {"tony", "pat", "bob", "carol", rootName}) {
				ParleyPrincipal u = registry.createUser(name);
				registry.grant(u, new BasicParleyPermission(StandardPermissions.READ, export.getId()));
				registry.grant(u, new BasicParleyPermission(StandardPermissions.WRITE, export.getId()));
				if( name.equals("pat") ) {
					registry.addMember(staff, u);
				}
				if( name.equals("carol") ) {
					registry.setAttribute(u, "uid", "3000");
				}
			}
		}
		infos.clear();
		server = new NfsServer("test.acl", 0) {
			@Override
			public void logInfo(String message) {
				infos.add(message);
				super.logInfo(message);
			}
		};
		server.setExport(export);
		server.setUserRegistry(registry);
		server.startAndWait(10000);
	}

	private void stopServer() throws Exception {
		if( server != null ) {
			server.stop();
			long end = System.currentTimeMillis()+10000;
			while( server.isRunning() && System.currentTimeMillis() < end ) {
				Thread.sleep(20);
			}
			assertFalse(server.isRunning());
			assertTrue(server.awaitStopped(10000));
			server = null;
		}
	}

	private TestClient as(int uid) throws IOException {
		TestClient c = new TestClient(server.getLocalPort());
		c.uid = uid;
		clients.add(c);
		return c;
	}

	@AfterEach
	public void tearDown() throws Exception {
		for (TestClient c : clients) {
			c.close();
		}
		stopServer();
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
	}

	/** PUTFH dir, OPEN, GETFH; the open is confirmed. */
	private static Opened open(TestClient c, byte[] dir, long clientid, int access, boolean create, String name)
			throws IOException {
		Ops ops = new Ops().putfh(dir).op(Nfs4.OP_OPEN);
		XdrEncoder a = ops.args();
		a.writeInt(1).writeInt(access).writeInt(0).writeLong(clientid).writeOpaque(bytes("owner"+c.uid+"-"+(++ownerCount)));
		if( create ) {
			a.writeInt(Nfs4.OPEN4_CREATE).writeInt(Nfs4.UNCHECKED4).writeIntArray(new int[0]).writeInt(0);
		} else {
			a.writeInt(Nfs4.OPEN4_NOCREATE);
		}
		a.writeInt(Nfs4.CLAIM_NULL).writeString(name);
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
		assertEquals(Nfs4.OPEN_DELEGATE_NONE, r.in.readInt());
		r.ok(Nfs4.OP_GETFH);
		o.fh = r.in.readOpaque(128);
		if( (rflags & Nfs4.OPEN4_RESULT_CONFIRM) != 0 ) {
			Ops conf = new Ops().putfh(o.fh).op(Nfs4.OP_OPEN_CONFIRM);
			conf.args().writeInt(o.seqid).writeFixedOpaque(o.other).writeInt(2);
			Result cr = c.compound(conf);
			cr.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_OPEN_CONFIRM);
			o.seqid = cr.in.readInt();
			o.other = cr.in.readFixedOpaque(12);
		}
		return o;
	}

	private static int write(TestClient c, byte[] fh, Opened o, byte[] data) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_WRITE);
		ops.args().writeInt(o.seqid).writeFixedOpaque(o.other).writeLong(0).writeInt(Nfs4.FILE_SYNC4).writeOpaque(data);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_WRITE);
	}

	private static int read(TestClient c, byte[] fh) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_READ);
		ops.args().writeInt(0).writeFixedOpaque(new byte[12]).writeLong(0).writeInt(100);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_READ);
	}

	private static byte[] lookup(TestClient c, byte[] dir, String name) throws IOException {
		Result r = c.compound(new Ops().putfh(dir).lookup(name).getfh());
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_LOOKUP).ok(Nfs4.OP_GETFH);
		return r.in.readOpaque(128);
	}

	private static int lookupStatus(TestClient c, byte[] dir, String name) throws IOException {
		Result r = c.compound(new Ops().putfh(dir).lookup(name));
		r.ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_LOOKUP);
	}

	private static byte[] mkdir(TestClient c, byte[] dir, String name) throws IOException {
		Ops ops = new Ops().putfh(dir).op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4DIR).writeString(name).writeIntArray(new int[0]).writeInt(0);
		ops.getfh();
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_CREATE);
		r.in.readBoolean();
		r.in.readLong();
		r.in.readLong();
		r.in.readIntArray(8);
		r.ok(Nfs4.OP_GETFH);
		return r.in.readOpaque(128);
	}

	private static int mkdirStatus(TestClient c, byte[] dir, String name) throws IOException {
		Ops ops = new Ops().putfh(dir).op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4DIR).writeString(name).writeIntArray(new int[0]).writeInt(0);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_CREATE);
	}

	private static int remove(TestClient c, byte[] dir, String name) throws IOException {
		Ops ops = new Ops().putfh(dir).op(Nfs4.OP_REMOVE);
		ops.args().writeString(name);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_REMOVE);
	}

	private static int rename(TestClient c, byte[] from, String oldName, byte[] to, String newName) throws IOException {
		Ops ops = new Ops().putfh(from).op(Nfs4.OP_SAVEFH).putfh(to).op(Nfs4.OP_RENAME);
		ops.args().writeString(oldName).writeString(newName);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_SAVEFH).ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_RENAME);
	}

	/** Makes this user the file's owner (a new file's owner isn't set by the server). */
	private static void own(TestClient c, byte[] fh, String name) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_SETATTR);
		ops.args().writeInt(0).writeFixedOpaque(new byte[12])
			.writeIntArray(Attributes.bitmap(Nfs4.FATTR4_OWNER)).writeOpaque(new XdrEncoder().writeString(name).toByteArray());
		assertEquals(Nfs4.NFS4_OK, c.compound(ops).status);
	}

	private static byte[] aclValue(List<Ace> acl) {
		XdrEncoder e = new XdrEncoder().writeInt(acl.size());
		for (Ace a : acl) {
			e.writeInt(a.getType()).writeInt(a.getFlags()).writeInt(a.getMask()).writeString(a.getWho());
		}
		return e.toByteArray();
	}

	/** SETATTR of the acl attribute; the status of the SETATTR (the COMPOUND's when it failed earlier). */
	private static int setAcl(TestClient c, byte[] fh, List<Ace> acl) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_SETATTR);
		ops.args().writeInt(0).writeFixedOpaque(new byte[12])
			.writeIntArray(Attributes.bitmap(Nfs4.FATTR4_ACL)).writeOpaque(aclValue(acl));
		return c.compound(ops).status;
	}

	private static List<Ace> getAcl(TestClient c, byte[] fh) throws IOException {
		Result r = c.compound(new Ops().putfh(fh).getattr(Nfs4.FATTR4_ACL));
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_GETATTR);
		int[] bits = r.in.readIntArray(8);
		if( !Attributes.isSet(bits, Nfs4.FATTR4_ACL) ) {
			return null;
		}
		r.in.readInt();
		int n = r.in.readInt();
		List<Ace> ret = new ArrayList<Ace>();
		for (int i = 0; i < n; i++) {
			ret.add(new Ace(r.in.readInt(), r.in.readInt(), r.in.readInt(), r.in.readString(1024)));
		}
		return ret;
	}

	private static int aclSupport(TestClient c, byte[] fh) throws IOException {
		Result r = c.compound(new Ops().putfh(fh).getattr(Nfs4.FATTR4_ACLSUPPORT));
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_GETATTR);
		r.in.readIntArray(8);
		r.in.readInt();
		return r.in.readInt();
	}

	// ------------------------------------------------------------------ tests

	/** Decision D51: without a state folder there are no lists. */
	@Test
	public void withoutAStateFolderThereAreNoLists(@TempDir Path tmp) throws Exception {
		start(tmp, false);
		TestClient c = as(TONY);
		byte[] rootFh = rootFh(c);
		assertEquals(0, aclSupport(c, rootFh));
		assertNull(getAcl(c, rootFh), "the acl attribute isn't returned");
		assertEquals(Nfs4.NFS4ERR_ATTRNOTSUPP, setAcl(c, rootFh, Arrays.asList(Ace.allow(0, READ, Ace.EVERYONE))));
		assertEquals(Nfs4.NFS4ERR_ATTRNOTSUPP, setAcl(c, rootFh, Arrays.asList(Ace.allow(0, READ, Ace.EVERYONE))));
		assertEquals(1, infos.stream().filter(m -> m.contains("tried to set an access control list")).count(),
				"said once: "+infos);
		assertTrue(infos.stream().anyMatch(m -> m.contains("no test.acl.stateDir") && m.contains("access control lists can't be set")),
				"the start says what the missing folder costs: "+infos);
	}

	/** A state folder setting that names a file is refused at start, and the message says why. */
	@Test
	public void aStateFolderThatIsAFileIsSaidSo(@TempDir Path tmp) throws Exception {
		Files.write(tmp.resolve("state"), new byte[] {1});
		Exception e = assertThrows(Exception.class, () -> start(tmp, true));
		StringBuilder all = new StringBuilder();
		for (Throwable t = e; t != null; t = t.getCause()) {
			all.append(t.getMessage()).append(" | ");
		}
		assertTrue(all.toString().contains("is a file: it has to be a folder"), all.toString());
	}

	@Test
	public void aListIsKeptAndReturned(@TempDir Path tmp) throws Exception {
		start(tmp, true);
		TestClient c = as(TONY);
		long id = clientid(c, "tony");
		byte[] rootFh = rootFh(c);
		assertEquals(Nfs4.ACL4_SUPPORT_ALLOW_ACL | Nfs4.ACL4_SUPPORT_DENY_ACL, aclSupport(c, rootFh));
		Opened o = open(c, rootFh, id, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "f");
		assertEquals(Nfs4.NFS4_OK, o.status);

		List<Ace> fromMode = getAcl(c, o.fh);
		assertEquals(3, fromMode.size(), "a file with no list shows one made from its mode");
		assertEquals(Ace.OWNER, fromMode.get(0).getWho());
		assertEquals(Ace.EVERYONE, fromMode.get(2).getWho());

		List<Ace> acl = Arrays.asList(Ace.allow(0, READ|ATTRS, "pat"), Ace.deny(0, WRITE, "bob"),
				Ace.allow(Nfs4.ACE4_IDENTIFIER_GROUP, READ, "staff"), Ace.allow(0, READ, Ace.EVERYONE),
				Ace.allow(0, Nfs4.ACE4_WRITE_ACL, "tony"));
		assertEquals(Nfs4.NFS4_OK, setAcl(c, o.fh, acl));
		List<Ace> back = getAcl(c, o.fh);
		assertEquals(acl.size(), back.size());
		assertEquals("pat@example.org", back.get(0).getWho(), "names are returned with the domain");
		assertEquals(READ|ATTRS, back.get(0).getMask());
		assertTrue(back.get(1).isDeny());
		assertTrue(back.get(2).isGroup());
		assertEquals(Ace.EVERYONE, back.get(3).getWho());

		// what a client may not send
		assertEquals(Nfs4.NFS4ERR_ATTRNOTSUPP, setAcl(c, o.fh,
				Arrays.asList(new Ace(Nfs4.ACE4_SYSTEM_AUDIT_ACE_TYPE, 0, READ, Ace.EVERYONE))));
		assertEquals(Nfs4.NFS4ERR_INVAL, setAcl(c, o.fh, Arrays.asList(Ace.allow(0, 0x4000, Ace.EVERYONE))));
		assertEquals(Nfs4.NFS4ERR_INVAL, setAcl(c, o.fh, Arrays.asList(Ace.allow(0x100, READ, Ace.EVERYONE))));
		assertEquals(Nfs4.NFS4ERR_BADOWNER, setAcl(c, o.fh, Arrays.asList(Ace.allow(0, READ, ""))));
		assertEquals(acl.size(), getAcl(c, o.fh).size(), "refused lists changed nothing");
		assertEquals(Nfs4.NFS4_OK, setAcl(c, o.fh, new ArrayList<Ace>()), "an empty list is allowed");
		assertEquals(0, getAcl(c, o.fh).size());
	}

	/** Decisions D52 and D53: a user, a group, a number, EVERYONE@, and a deny before an allow. */
	@Test
	public void aListDecidesWhoMayDoWhat(@TempDir Path tmp) throws Exception {
		start(tmp, true);
		TestClient tony = as(TONY);
		long tonyId = clientid(tony, "tony");
		byte[] rootFh = rootFh(tony);
		Opened o = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "f");
		assertEquals(Nfs4.NFS4_OK, write(tony, o.fh, o, bytes("hello")));
		byte[] fh = o.fh;

		TestClient pat = as(PAT);
		TestClient bob = as(BOB);
		TestClient carol = as(CAROL);
		long patId = clientid(pat, "pat");
		long bobId = clientid(bob, "bob");
		long carolId = clientid(carol, "carol");

		// no list: the registry alone decides, as before
		assertEquals(Nfs4.NFS4_OK, open(bob, rootFh, bobId, Nfs4.OPEN4_SHARE_ACCESS_READ, false, "f").status);

		List<Ace> acl = Arrays.asList(
				Ace.deny(0, READ, "bob"),                                  // before the allow for everyone
				Ace.allow(0, READ, "pat"),                                 // by name
				Ace.allow(Nfs4.ACE4_IDENTIFIER_GROUP, WRITE, "staff"),     // by group (pat is in staff)
				Ace.allow(0, READ, "3000"),                                // by number: carol's uid attribute
				Ace.allow(0, EXEC, Ace.EVERYONE));
		assertEquals(Nfs4.NFS4_OK, setAcl(tony, fh, acl));

		assertEquals(Nfs4.NFS4_OK, open(pat, rootFh, patId, Nfs4.OPEN4_SHARE_ACCESS_READ, false, "f").status);
		assertEquals(Nfs4.NFS4ERR_ACCESS, open(bob, rootFh, bobId, Nfs4.OPEN4_SHARE_ACCESS_READ, false, "f").status,
				"denied by name");
		assertEquals(Nfs4.NFS4_OK, open(carol, rootFh, carolId, Nfs4.OPEN4_SHARE_ACCESS_READ, false, "f").status,
				"allowed by the registry user's uid attribute");
		Opened patWrite = open(pat, rootFh, patId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, false, "f");
		assertEquals(Nfs4.NFS4_OK, patWrite.status, "allowed as a member of staff");
		assertEquals(Nfs4.NFS4_OK, write(pat, fh, patWrite, bytes("x")));
		assertEquals(Nfs4.NFS4ERR_ACCESS, open(carol, rootFh, carolId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, false, "f").status,
				"nothing gives carol write");
		assertEquals(Nfs4.NFS4ERR_ACCESS, read(bob, fh), "READ checks the list too (not only OPEN)");
	}

	/** The registry's check stays the outer gate (decision D52): a list can't give what the registry refuses. */
	@Test
	public void theRegistryStillChecksFirst(@TempDir Path tmp) throws Exception {
		start(tmp, true);
		ParleyPrincipal eve = registry.createUser("eve");
		registry.grant(eve, new BasicParleyPermission(StandardPermissions.READ, export.getId()));
		stopServer();
		start(tmp, true);
		TestClient tony = as(TONY);
		long tonyId = clientid(tony, "tony");
		byte[] rootFh = rootFh(tony);
		Opened o = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "f");
		assertEquals(Nfs4.NFS4_OK, setAcl(tony, o.fh, Arrays.asList(Ace.allow(0, READ|WRITE, Ace.EVERYONE))));
		TestClient e = as(1004);
		long eveId = clientid(e, "eve");
		assertEquals(Nfs4.NFS4_OK, open(e, rootFh, eveId, Nfs4.OPEN4_SHARE_ACCESS_READ, false, "f").status);
		assertEquals(Nfs4.NFS4ERR_ACCESS, open(e, rootFh, eveId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, false, "f").status,
				"the list allows write but the registry gave eve read only");
	}

	/** Decision D53: what a folder's list passes down to new files and folders. */
	@Test
	public void newFilesInheritTheFoldersEntries(@TempDir Path tmp) throws Exception {
		start(tmp, true);
		TestClient tony = as(TONY);
		long tonyId = clientid(tony, "tony");
		byte[] rootFh = rootFh(tony);
		byte[] dir = mkdir(tony, rootFh, "d");
		List<Ace> acl = Arrays.asList(
				Ace.allow(Nfs4.ACE4_FILE_INHERIT_ACE, READ, "bob"),
				Ace.allow(Nfs4.ACE4_DIRECTORY_INHERIT_ACE, READ|WRITE, "pat"),
				Ace.allow(Nfs4.ACE4_FILE_INHERIT_ACE | Nfs4.ACE4_DIRECTORY_INHERIT_ACE | Nfs4.ACE4_NO_PROPAGATE_INHERIT_ACE,
						READ, "carol"),
				Ace.allow(0, READ|WRITE|EXEC|Nfs4.ACE4_ADD_SUBDIRECTORY, "tony"),
				Ace.allow(0, EXEC|READ, Ace.EVERYONE));
		assertEquals(Nfs4.NFS4_OK, setAcl(tony, dir, acl));

		Opened o = open(tony, dir, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "f");
		assertEquals(Nfs4.NFS4_OK, o.status);
		List<Ace> file = getAcl(tony, o.fh);
		assertEquals(2, file.size(), "a file gets the file-inherit entries, flags cleared: "+file);
		assertEquals("bob@example.org", file.get(0).getWho());
		assertEquals(0, file.get(0).getFlags());
		assertEquals("carol@example.org", file.get(1).getWho());

		byte[] sub = mkdir(tony, dir, "s");
		List<Ace> folder = getAcl(tony, sub);
		assertEquals(3, folder.size(), "a folder gets directory-inherit entries and passes on file-inherit ones: "+folder);
		assertEquals("bob@example.org", folder.get(0).getWho());
		assertTrue((folder.get(0).getFlags() & Nfs4.ACE4_INHERIT_ONLY_ACE) != 0, "only for its files");
		assertTrue((folder.get(0).getFlags() & Nfs4.ACE4_FILE_INHERIT_ACE) != 0);
		assertEquals("pat@example.org", folder.get(1).getWho());
		assertEquals(Nfs4.ACE4_DIRECTORY_INHERIT_ACE, folder.get(1).getFlags());
		assertEquals("carol@example.org", folder.get(2).getWho());
		assertEquals(0, folder.get(2).getFlags() & (Nfs4.ACE4_FILE_INHERIT_ACE | Nfs4.ACE4_DIRECTORY_INHERIT_ACE
				| Nfs4.ACE4_NO_PROPAGATE_INHERIT_ACE), "no-propagate stops here");

		// the entry of an inherit-only kind doesn't apply to the folder itself
		TestClient bob = as(BOB);
		long bobId = clientid(bob, "bob");
		assertEquals(Nfs4.NFS4_OK, open(bob, dir, bobId, Nfs4.OPEN4_SHARE_ACCESS_READ, false, "f").status,
				"bob reads the new file through the inherited entry");
	}

	/** Decision D52: removing needs DELETE_CHILD on the folder or DELETE on the file; rename adds the new folder's ADD. */
	@Test
	public void removeAndRenameFollowTheLists(@TempDir Path tmp) throws Exception {
		start(tmp, true);
		TestClient tony = as(TONY);
		long tonyId = clientid(tony, "tony");
		TestClient pat = as(PAT);
		byte[] rootFh = rootFh(tony);
		byte[] dir = mkdir(tony, rootFh, "d");
		assertEquals(Nfs4.NFS4_OK, open(tony, dir, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "a").status);
		assertEquals(Nfs4.NFS4_OK, open(tony, dir, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "b").status);
		assertEquals(Nfs4.NFS4_OK, open(tony, dir, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "c").status);
		// the folder: everyone lists and looks up, but only tony may remove or add
		assertEquals(Nfs4.NFS4_OK, setAcl(tony, dir, Arrays.asList(
				Ace.allow(0, READ|EXEC|Nfs4.ACE4_DELETE_CHILD|Nfs4.ACE4_ADD_FILE|Nfs4.ACE4_ADD_SUBDIRECTORY, "tony"),
				Ace.allow(0, READ|EXEC, Ace.EVERYONE))));
		assertEquals(Nfs4.NFS4ERR_ACCESS, remove(pat, dir, "a"), "no DELETE_CHILD");
		assertEquals(Nfs4.NFS4ERR_ACCESS, mkdirStatus(pat, dir, "x"), "no ADD_SUBDIRECTORY");
		assertEquals(Nfs4.NFS4ERR_ACCESS, rename(pat, dir, "b", dir, "b2"));
		assertTrue(root.getChild("d").getChild("a").exists());
		// a file's own DELETE is enough
		assertEquals(Nfs4.NFS4_OK, setAcl(tony, lookup(tony, dir, "a"),
				Arrays.asList(Ace.allow(0, Nfs4.ACE4_DELETE, "pat"))));
		assertEquals(Nfs4.NFS4_OK, remove(pat, dir, "a"));
		assertFalse(root.getChild("d").getChild("a").exists());
		assertEquals(Nfs4.NFS4_OK, rename(tony, dir, "b", dir, "b2"));
		assertTrue(root.getChild("d").getChild("b2").exists());
		assertEquals(Nfs4.NFS4_OK, remove(tony, dir, "c"));
	}

	/** Decision D51: a list follows its file through a rename and ends with it. */
	@Test
	public void aListFollowsARenameAndEndsWithItsFile(@TempDir Path tmp) throws Exception {
		start(tmp, true);
		TestClient tony = as(TONY);
		long tonyId = clientid(tony, "tony");
		byte[] rootFh = rootFh(tony);
		Opened o = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "f");
		List<Ace> acl = Arrays.asList(Ace.allow(0, READ|WRITE|ATTRS|Nfs4.ACE4_DELETE, "tony"), Ace.deny(0, READ, Ace.EVERYONE));
		assertEquals(Nfs4.NFS4_OK, setAcl(tony, o.fh, acl));
		assertEquals(Nfs4.NFS4_OK, rename(tony, rootFh, "f", rootFh, "g"));
		assertEquals(2, getAcl(tony, lookup(tony, rootFh, "g")).size(), "the list moved with the file");
		assertNull(export.getAcls().get(export.getNodes().get("f").getFileid()), "nothing at the old path");

		assertEquals(Nfs4.NFS4_OK, remove(tony, rootFh, "g"));
		Opened again = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "g");
		assertEquals(3, getAcl(tony, again.fh).size(), "a new file at the old path has none (its list is made from its mode)");
		assertNull(export.getAcls().get(export.getNodes().get("g").getFileid()));

		// a file renamed over another takes the other's place without its list
		Opened x = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "x");
		Opened y = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "y");
		assertEquals(Nfs4.NFS4_OK, setAcl(tony, y.fh, Arrays.asList(Ace.allow(0, READ|WRITE|ATTRS|Nfs4.ACE4_DELETE, "tony"))));
		assertEquals(Nfs4.NFS4_OK, rename(tony, rootFh, "x", rootFh, "y"));
		assertEquals(3, getAcl(tony, lookup(tony, rootFh, "y")).size(), "x had no list, and y's went with the file it was");
		assertNotNull(x);
	}

	/** Decision D51: lists are in the state folder and survive a restart. */
	@Test
	public void listsSurviveARestart(@TempDir Path tmp) throws Exception {
		start(tmp, true);
		TestClient tony = as(TONY);
		long tonyId = clientid(tony, "tony");
		byte[] rootFh = rootFh(tony);
		Opened o = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "f");
		List<Ace> acl = Arrays.asList(Ace.allow(0, READ|ATTRS, "pat"), Ace.deny(Nfs4.ACE4_IDENTIFIER_GROUP, WRITE, "staff"),
				Ace.allow(Nfs4.ACE4_FILE_INHERIT_ACE, READ, "name\twith\ttabs"));
		assertEquals(Nfs4.NFS4_OK, setAcl(tony, o.fh, acl));
		stopServer();
		clients.clear();

		start(tmp, true);
		TestClient t2 = as(TONY);
		byte[] root2 = rootFh(t2);
		byte[] fh = lookup(t2, root2, "f");
		List<Ace> back = getAcl(t2, fh);
		assertEquals(3, back.size());
		assertEquals(READ|ATTRS, back.get(0).getMask());
		assertEquals("pat@example.org", back.get(0).getWho());
		assertTrue(back.get(1).isDeny() && back.get(1).isGroup());
		assertEquals(Nfs4.ACE4_FILE_INHERIT_ACE, back.get(2).getFlags());
		assertTrue(back.get(2).getWho().startsWith("name\twith\ttabs"), back.get(2).getWho());
		try (java.util.stream.Stream<Path> files = Files.list(tmp.resolve("state"))) {
			assertEquals(1, files.filter(f -> f.getFileName().toString().endsWith(".acls")).count());
		}
	}

	/** ACCESS reports what the list gives. */
	@Test
	public void accessFollowsTheList(@TempDir Path tmp) throws Exception {
		start(tmp, true);
		TestClient tony = as(TONY);
		long tonyId = clientid(tony, "tony");
		byte[] rootFh = rootFh(tony);
		Opened o = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "f");
		assertEquals(Nfs4.NFS4_OK, setAcl(tony, o.fh, Arrays.asList(Ace.allow(0, READ, "pat"))));
		TestClient pat = as(PAT);
		TestClient bob = as(BOB);
		int all = Nfs4.ACCESS4_READ | Nfs4.ACCESS4_MODIFY | Nfs4.ACCESS4_EXTEND;
		assertEquals(Nfs4.ACCESS4_READ, access(pat, o.fh, all));
		assertEquals(0, access(bob, o.fh, all));
	}

	private static int access(TestClient c, byte[] fh, int asked) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_ACCESS);
		ops.args().writeInt(asked);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_ACCESS);
		r.in.readInt();
		return r.in.readInt();
	}

	// ------------------------------------------------------------------ the rules without a server

	@Test
	public void aDenyAfterAnAllowIsTooLate() {
		List<Ace> acl = Arrays.asList(Ace.allow(0, READ, Ace.EVERYONE), Ace.deny(0, READ, Ace.EVERYONE));
		assertTrue(Acls.allows(acl, READ, a -> true));
		List<Ace> reversed = Arrays.asList(acl.get(1), acl.get(0));
		assertFalse(Acls.allows(reversed, READ, a -> true));
		assertFalse(Acls.allows(acl, READ|WRITE, a -> true), "write is granted by nobody");
		assertFalse(Acls.allows(new ArrayList<Ace>(), READ, a -> true), "an empty list grants nothing");
		assertTrue(Acls.allows(new ArrayList<Ace>(), 0, a -> true));
		assertFalse(Acls.allows(Arrays.asList(Ace.allow(Nfs4.ACE4_INHERIT_ONLY_ACE | Nfs4.ACE4_FILE_INHERIT_ACE, READ, Ace.EVERYONE)),
				READ, a -> true), "inherit-only entries don't apply");
	}

	/** Sets the nine bits of a file or folder from a mode such as 0640. */
	private static void mode(FileSource f, int m) throws IOException {
		assertTrue(f.setOwnerReadable((m & 0400) != 0) & f.setOwnerWritable((m & 0200) != 0) & f.setOwnerExecutable((m & 0100) != 0)
				& f.setGroupReadable((m & 040) != 0) & f.setGroupWritable((m & 020) != 0) & f.setGroupExecutable((m & 010) != 0)
				& f.setOtherReadable((m & 04) != 0) & f.setOtherWritable((m & 02) != 0) & f.setOtherExecutable((m & 01) != 0));
	}

	/** Decision D73: with enforceModes the mode bits decide, by the caller's name, for a file that has no list. */
	@Test
	public void enforcedModesDecideForFilesWithoutAList(@TempDir Path tmp) throws Exception {
		property("test.acl.enforceModes", "true");
		start(tmp, false);
		FileSourceUser tonyUser = new FileSourceUser(TONY, "tony");
		mode(root, 0777);
		TestClient tony = as(TONY);
		TestClient pat = as(PAT);
		TestClient bob = as(BOB);
		long tonyId = clientid(tony, "tony");
		long patId = clientid(pat, "pat");
		long bobId = clientid(bob, "bob");
		byte[] rootFh = rootFh(tony);

		Opened o = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "f");
		assertEquals(Nfs4.NFS4_OK, o.status);
		assertEquals(Nfs4.NFS4_OK, write(tony, o.fh, o, bytes("hi")));
		FileSource f = root.getChild("f");
		assertTrue(f.setOwner(tonyUser));
		mode(f, 0600);
		assertEquals(Nfs4.NFS4_OK, read(tony, o.fh), "the owner reads");
		assertEquals(Nfs4.NFS4ERR_ACCESS, read(pat, o.fh), "another user may not read a file that is 0600");
		assertEquals(Nfs4.NFS4ERR_ACCESS, open(pat, rootFh, patId, Nfs4.OPEN4_SHARE_ACCESS_READ, false, "f").status);
		assertEquals(Nfs4.NFS4ERR_ACCESS, open(bob, rootFh, bobId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, false, "f").status);
		mode(f, 0644);
		assertEquals(Nfs4.NFS4_OK, read(pat, o.fh), "the other bits now allow reading");
		assertEquals(Nfs4.NFS4ERR_ACCESS, open(bob, rootFh, bobId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, false, "f").status,
				"but not writing");
		assertEquals(Nfs4.NFS4_OK, open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, false, "f").status);

		// a folder that others may list but not change
		byte[] dir = mkdir(tony, rootFh, "d");
		FileSource d = root.getChild("d");
		assertTrue(d.setOwner(tonyUser));
		mode(d, 0755);
		assertEquals(Nfs4.NFS4_OK, open(tony, dir, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "a").status);
		assertEquals(Nfs4.NFS4ERR_ACCESS, mkdirStatus(pat, dir, "x"), "no write bit for others on the folder");
		assertEquals(Nfs4.NFS4ERR_ACCESS, remove(pat, dir, "a"));
		assertEquals(Nfs4.NFS4ERR_ACCESS, rename(pat, dir, "a", dir, "b"));
		assertTrue(root.getChild("d").getChild("a").exists());
		assertEquals(Nfs4.NFS4_OK, rename(tony, dir, "a", dir, "b"));
		assertEquals(Nfs4.NFS4_OK, remove(tony, dir, "b"));
		mode(d, 0700);
		assertEquals(Nfs4.NFS4ERR_ACCESS, lookupStatus(pat, dir, "anything"), "no execute bit for others");
	}

	/** The default is the old behavior: the modes are not checked (nothing changes until the setting is on). */
	@Test
	public void modesAreNotCheckedByDefault(@TempDir Path tmp) throws Exception {
		start(tmp, false);
		mode(root, 0777);
		TestClient tony = as(TONY);
		TestClient pat = as(PAT);
		long tonyId = clientid(tony, "tony");
		clientid(pat, "pat");
		byte[] rootFh = rootFh(tony);
		Opened o = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "f");
		assertEquals(Nfs4.NFS4_OK, o.status);
		mode(root.getChild("f"), 0600);
		assertEquals(Nfs4.NFS4_OK, read(pat, o.fh));
	}

	private static int chown(TestClient c, byte[] fh, String name) throws IOException {
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_SETATTR);
		ops.args().writeInt(0).writeFixedOpaque(new byte[12])
			.writeIntArray(Attributes.bitmap(Nfs4.FATTR4_OWNER)).writeOpaque(new XdrEncoder().writeString(name).toByteArray());
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_SETATTR);
	}

	/** Decision D74: only the root that is mapped to a registry user may change an owner. */
	@Test
	public void onlyTheMappedRootMayChangeAnOwner(@TempDir Path tmp) throws Exception {
		rootName = "rootuser";
		property("test.acl.enforceModes", "true");
		start(tmp, false);
		mode(root, 0777);
		TestClient tony = as(TONY);
		TestClient admin = as(0);
		long tonyId = clientid(tony, "tony");
		clientid(admin, "admin");
		byte[] rootFh = rootFh(tony);
		Opened o = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "f");
		assertEquals(Nfs4.NFS4_OK, o.status);
		FileSource f = root.getChild("f");
		assertTrue(f.setOwner(new FileSourceUser(TONY, "tony")));
		mode(f, 0600);
		// with the modes enforced the owner has no WRITE_OWNER, so the refusal is ACCESS; without them it is PERM
		int own = chown(tony, o.fh, "pat");
		assertTrue(own == Nfs4.NFS4ERR_PERM || own == Nfs4.NFS4ERR_ACCESS, "the owner may not give a file away: "+own);
		int other = chown(as(PAT), o.fh, "pat");
		assertTrue(other == Nfs4.NFS4ERR_PERM || other == Nfs4.NFS4ERR_ACCESS, "nor may another user: "+other);
		// root is let through whatever the mode and the lists say; the memory file system takes any name
		assertEquals(Nfs4.NFS4_OK, chown(admin, o.fh, "pat@example.org"));
		assertEquals("pat", f.getOwner().getName());
		assertEquals(Nfs4.NFS4_OK, chown(admin, o.fh, Long.toString(BOB)), "a number goes through the id map");
		assertEquals("bob", f.getOwner().getName());
		// a number nobody maps goes to the provider as it is (a local file takes it as the OS uid, D75)
		assertEquals(Nfs4.NFS4_OK, chown(admin, o.fh, "4242"), "a number nobody maps");
		assertEquals("4242", f.getOwner().getName());
	}

	/** Decision D76: the mapped root is not held to mode bits (as on a Unix file system), everyone else still is. */
	@Test
	public void theMappedRootIsNotHeldToModes(@TempDir Path tmp) throws Exception {
		rootName = "rootuser";
		property("test.acl.enforceModes", "true");
		start(tmp, false);
		mode(root, 0777);
		FileSourceUser tonyUser = new FileSourceUser(TONY, "tony");
		TestClient tony = as(TONY);
		TestClient pat = as(PAT);
		TestClient admin = as(0);
		long tonyId = clientid(tony, "tony");
		clientid(pat, "pat");
		clientid(admin, "admin");
		byte[] rootFh = rootFh(tony);
		Opened o = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "f");
		assertEquals(Nfs4.NFS4_OK, o.status);
		FileSource f = root.getChild("f");
		assertTrue(f.setOwner(tonyUser));
		mode(f, 0600);
		byte[] dir = mkdir(tony, rootFh, "d");
		FileSource d = root.getChild("d");
		assertTrue(d.setOwner(tonyUser));
		mode(d, 0700);

		assertEquals(Nfs4.NFS4ERR_ACCESS, read(pat, o.fh), "another user is held to the mode");
		assertEquals(Nfs4.NFS4ERR_ACCESS, lookupStatus(pat, dir, "x"), "and to the folder's mode");
		assertEquals(Nfs4.NFS4_OK, read(admin, o.fh), "the mapped root reads a file that is 0600");
		assertEquals(Nfs4.NFS4_OK, open(admin, rootFh, clientid(admin, "admin2"), Nfs4.OPEN4_SHARE_ACCESS_WRITE, false, "f").status,
				"and opens it for writing");
		assertEquals(Nfs4.NFS4_OK, mkdirStatus(admin, dir, "made-by-root"), "and makes a folder in one that is 0700");
		assertEquals(Nfs4.NFS4ERR_ACCESS, mkdirStatus(pat, dir, "x"));
		assertEquals(Nfs4.NFS4_OK, remove(admin, rootFh, "f"), "and removes a file it doesn't own");
	}

	/** Decision D74: root that the id map doesn't map is the anonymous user and may not change an owner. */
	@Test
	public void anUnmappedRootMayNotChangeAnOwner(@TempDir Path tmp) throws Exception {
		start(tmp, false);
		TestClient tony = as(TONY);
		TestClient admin = as(0);
		long tonyId = clientid(tony, "tony");
		byte[] rootFh = rootFh(tony);
		Opened o = open(tony, rootFh, tonyId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "f");
		assertEquals(Nfs4.NFS4_OK, o.status);
		int status = chown(admin, o.fh, "nosuchuser");
		assertTrue(status == Nfs4.NFS4ERR_PERM || status == Nfs4.NFS4ERR_ACCESS, "root is only the anonymous user here: "+status);
	}

	/** Decision D73: a new file is given to the caller who made it, when the file system can. */
	@Test
	public void aNewFileBelongsToItsCaller(@TempDir Path tmp) throws Exception {
		property("test.acl.enforceModes", "true");
		start(tmp, false);
		mode(root, 0777);
		TestClient pat = as(PAT);
		long patId = clientid(pat, "pat");
		byte[] rootFh = rootFh(pat);
		assertTrue(infos.stream().anyMatch(m -> m.contains("mode bits of files are checked")), infos.toString());
		assertEquals(Nfs4.NFS4_OK, open(pat, rootFh, patId, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "one").status);
		mkdir(pat, rootFh, "two");
		assertEquals("pat", root.getChild("one").getOwner().getName());
		assertEquals("pat", root.getChild("two").getOwner().getName());
		assertTrue(infos.stream().noneMatch(m -> m.contains("keeps the owner")), infos.toString());
	}

	/** Decision D73: when the owner can't be set it is said once. */
	@Test
	public void aRefusedOwnerIsSaidOnce(@TempDir Path tmp) throws Exception {
		// a local export: the operating system has no user of this name, so FileProxy refuses
		rootName = "zz-no-such-os-user";
		localFolder = Files.createDirectory(tmp.resolve("export"));
		property("test.acl.enforceModes", "true");
		start(tmp, false);
		mode(root, 0777);
		TestClient admin = as(0);
		long id = clientid(admin, "admin");
		byte[] rootFh = rootFh(admin);
		assertEquals(Nfs4.NFS4_OK, open(admin, rootFh, id, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "one").status);
		mkdir(admin, rootFh, "two");
		assertEquals(Nfs4.NFS4_OK, open(admin, rootFh, id, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, "three").status);
		assertEquals(1, infos.stream().filter(m -> m.contains("keeps the owner the file system gave it")).count(), infos.toString());
	}

	@Test
	public void aModeMakesAList() {
		List<Ace> acl = Acls.fromMode(0640, false);
		assertEquals(3, acl.size());
		assertTrue((acl.get(0).getMask() & (Nfs4.ACE4_READ_DATA | Nfs4.ACE4_WRITE_DATA)) == (Nfs4.ACE4_READ_DATA | Nfs4.ACE4_WRITE_DATA));
		assertTrue((acl.get(0).getMask() & Nfs4.ACE4_WRITE_ACL) != 0, "the owner may change the list");
		assertTrue((acl.get(1).getMask() & Nfs4.ACE4_READ_DATA) != 0);
		assertEquals(0, acl.get(1).getMask() & Nfs4.ACE4_WRITE_DATA);
		assertEquals(0, acl.get(2).getMask() & Nfs4.ACE4_READ_DATA);
		assertArrayEquals(new String[] {Ace.OWNER, Ace.GROUP, Ace.EVERYONE},
				new String[] {acl.get(0).getWho(), acl.get(1).getWho(), acl.get(2).getWho()});
	}
}
