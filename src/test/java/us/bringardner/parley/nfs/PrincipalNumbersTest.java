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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

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
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.IdMap;
import us.bringardner.parley.nfs.server.IdNumbers;
import us.bringardner.parley.nfs.server.NfsServer;

/**
 * Decision D48: the numbers of users and groups are attributes of the registry's principals (uid, gid); the id map
 * is the fallback.
 */
public class PrincipalNumbersTest {

	private MemoryFileSourceFactory mem;
	private NfsServer server;
	private TestClient client;
	private final List<String> properties = new ArrayList<String>();

	@AfterEach
	public void tearDown() throws Exception {
		if( client != null ) {
			client.close();
		}
		if( server != null ) {
			server.stop();
			org.junit.jupiter.api.Assertions.assertTrue(server.awaitStopped(10000));
		}
		if( mem != null ) {
			mem.disConnect();
		}
		for (String p : properties) {
			System.clearProperty(p);
		}
	}

	private static IdMap idMap(Path dir, String content) throws IOException {
		Path f = dir.resolve("idmap-"+System.nanoTime()+".properties");
		Files.write(f, content.getBytes(StandardCharsets.UTF_8));
		return IdMap.load(us.bringardner.parley.files.FileSourceFactory.getDefaultFactory().resolve(f.toString()));
	}

	// ------------------------------------------------------------------ IdNumbers

	@Test
	public void aUidIsTheUserWhoseAttributeItIs(@TempDir Path tmp) throws Exception {
		BasicUserRegistry registry = new BasicUserRegistry(1000);
		registry.setAttribute(registry.createUser("tony"), "uid", "2000");
		registry.setAttribute(registry.createUser("pat"), "uid", "2001");
		registry.createUser("noid");
		registry.setAttribute(registry.createUser("twin1"), "uid", "7");
		registry.setAttribute(registry.createUser("twin2"), "uid", "7");
		ParleyPrincipal staff = registry.createGroup("staff");
		registry.setAttribute(staff, "gid", "50");
		// a group with a uid attribute isn't a user
		registry.setAttribute(registry.createGroup("odd"), "uid", "9");
		IdNumbers n = new IdNumbers(registry, idMap(tmp, "uid.1000=legacy\nuid.2001=old\nuid.7=zed\nuid.8=eight\n"), 60000);

		assertEquals("tony", n.user(2000));
		assertEquals("pat", n.user(2001), "the registry wins over the id map");
		assertEquals("legacy", n.user(1000), "a uid no attribute names comes from the id map");
		assertEquals("zed", n.user(7), "two users with one uid: the id map decides");
		assertEquals("eight", n.user(8));
		assertNull(n.user(9), "a group isn't a user");
		assertNull(n.user(4242));

		assertEquals(2000L, n.uid("tony"));
		assertEquals(1000L, n.uid("legacy"), "the id map is the fallback");
		assertNull(n.uid("noid"));
		assertEquals(50L, n.gid("staff"));
		assertNull(n.gid("tony"), "a user isn't a group");
		assertNull(n.gid("nobody"));
	}

	@Test
	public void withoutARegistryTheIdMapIsAll(@TempDir Path tmp) throws Exception {
		IdNumbers n = new IdNumbers(null, idMap(tmp, "uid.1000=tony\n"), 60000);
		assertEquals("tony", n.user(1000));
		assertEquals(1000L, n.uid("tony"));
		assertNull(n.gid("staff"));
		IdNumbers none = new IdNumbers(null, null, 60000);
		assertNull(none.user(1));
		assertNull(none.uid("x"));
	}

	// ------------------------------------------------------------------ over the wire

	private String attribute(byte[] fh, int attr) throws IOException {
		Result r = client.compound(new Ops().putfh(fh).getattr(attr));
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_GETATTR);
		r.in.readIntArray(8);
		r.in.readInt();
		return r.in.readString(1024);
	}

	/** No id map: the caller's uid is the user with that uid attribute, and the file's owner and group are sent as numbers. */
	@Test
	public void callersAndOwnersAreTheRegistrysNumbers(@TempDir Path tmp) throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource root = mem.createFileSource("/export");
		org.junit.jupiter.api.Assertions.assertTrue(root.mkdirs());
		Export export = new Export("test", root, false);
		String owner = root.getOwner().getName();
		String group = root.getGroup().getName();

		BasicUserRegistry registry = new BasicUserRegistry(1000);
		// the caller is named so that it can't be the user who owns the files (that is tony on a Mac)
		for (String name : new String[] {"caller", owner}) {
			ParleyPrincipal u = registry.createUser(name);
			registry.grant(u, new BasicParleyPermission(StandardPermissions.READ, export.getId()));
			registry.setAttribute(u, "uid", name.equals("caller") ? "2000" : "4321");
		}
		// users and groups share names in a registry, so a group named like the owner (root and root) can't be tested here
		boolean testGroup = !group.equals(owner);
		if( testGroup ) {
			registry.setAttribute(registry.createGroup(group), "gid", "99");
		}

		server = new NfsServer("test.num", 0);
		server.setExport(export);
		server.setUserRegistry(registry);
		server.startAndWait(10000);

		client = new TestClient(server.getLocalPort());
		client.uid = 2000;
		byte[] fh = client.compound(new Ops().putroot().getfh()).ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_GETFH).in.readOpaque(128);
		assertEquals("4321", attribute(fh, Nfs4.FATTR4_OWNER), "the owner as its uid attribute");
		if( testGroup ) {
			assertEquals("99", attribute(fh, Nfs4.FATTR4_OWNER_GROUP), "the group as its gid attribute");
		}

		// a uid no principal has is refused (no anonymous user)
		client.uid = 2999;
		assertEquals(Nfs4.NFS4ERR_ACCESS, client.compound(new Ops().putroot().getattr(Nfs4.FATTR4_TYPE)).status);
		// the owner by its number isn't a change
		client.uid = 2000;
		Ops ops = new Ops().putfh(fh).op(Nfs4.OP_SETATTR);
		ops.args().writeInt(0).writeFixedOpaque(new byte[12])
			.writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(Nfs4.FATTR4_OWNER))
			.writeOpaque(new us.bringardner.parley.nfs.rpc.XdrEncoder().writeString("4321").toByteArray());
		int status = client.compound(ops).status;
		assertEquals(Nfs4.NFS4ERR_ACCESS, status, "tony has read only, so SETATTR is refused before the owner is looked at");
	}
}
