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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
import us.bringardner.parley.nfs.server.NfsServer;

/** Decision D49: the nfs.root attribute confines a user to a folder of the export. */
public class RootFolderTest {

	private MemoryFileSourceFactory mem;
	private NfsServer server;
	private BasicUserRegistry registry;
	private final List<TestClient> clients = new ArrayList<TestClient>();

	@AfterEach
	public void tearDown() throws Exception {
		for (TestClient c : clients) {
			c.close();
		}
		if( server != null ) {
			server.stop();
			assertTrue(server.awaitStopped(10000));
		}
		if( mem != null ) {
			mem.disConnect();
		}
	}

	private FileSource file(FileSource root, String path, boolean dir) throws IOException {
		FileSource f = root;
		for (String part : path.split("/")) {
			f = f.getChild(part);
		}
		if( dir ) {
			assertTrue(f.mkdirs());
		} else {
			f.getParentFile().mkdirs();
			assertTrue(f.createNewFile());
		}
		return f;
	}

	/** users: tony (uid 1000, root home/tony), bob (1001, no root), eve (1002, root ../x), amy (1003, root nowhere) */
	private BasicUserRegistry users(Export... exports) throws Exception {
		BasicUserRegistry r = new BasicUserRegistry(1000);
		String[][] list = {{"tony", "1000", "/home/tony/"}, {"bob", "1001", null}, {"eve", "1002", "home/../x"},
			{"amy", "1003", "nowhere"}};
		for (String[] u : list) {
			ParleyPrincipal p = r.createUser(u[0]);
			for (Export e : exports) {
				r.grant(p, new BasicParleyPermission(StandardPermissions.READ, e.getId()));
				r.grant(p, new BasicParleyPermission(StandardPermissions.WRITE, e.getId()));
			}
			r.setAttribute(p, "uid", u[1]);
			if( u[2] != null ) {
				r.setAttribute(p, "nfs.root", u[2]);
			}
		}
		return r;
	}

	private void start(Export... exports) throws Exception {
		registry = users(exports);
		server = new NfsServer("test.root", 0);
		if( exports.length == 1 ) {
			server.setExport(exports[0]);
		} else {
			server.setExports(Arrays.asList(exports));
		}
		server.setUserRegistry(registry);
		server.startAndWait(10000);
	}

	private TestClient as(int uid) throws IOException {
		TestClient c = new TestClient(server.getLocalPort());
		c.uid = uid;
		clients.add(c);
		return c;
	}

	private static byte[] rootFh(TestClient c) throws IOException {
		Result r = c.compound(new Ops().putroot().getfh());
		r.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_GETFH);
		return r.in.readOpaque(128);
	}

	private static int lookup(TestClient c, byte[] dir, String name) throws IOException {
		Result r = c.compound(new Ops().putfh(dir).lookup(name));
		r.ok(Nfs4.OP_PUTFH);
		return r.next(Nfs4.OP_LOOKUP);
	}

	private static List<String> names(TestClient c, byte[] dir) throws IOException {
		Ops ops = new Ops().putfh(dir).op(Nfs4.OP_READDIR);
		ops.args().writeLong(0).writeFixedOpaque(new byte[8]).writeInt(4096).writeInt(8192).writeIntArray(new int[0]);
		Result r = c.compound(ops);
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_READDIR);
		r.in.readFixedOpaque(8);
		List<String> ret = new ArrayList<String>();
		while( r.in.readBoolean() ) {
			r.in.readLong();
			ret.add(r.in.readString(255));
			r.in.readIntArray(8);
			r.in.readOpaque(4096);
		}
		return ret;
	}

	@Test
	public void aUserSeesOnlyItsFolder() throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		file(root, "home/tony/f", false);
		file(root, "home/tony/sub", true);
		file(root, "home/bob/g", false);
		file(root, "top", false);
		start(new Export("test", root, false));

		TestClient tony = as(1000);
		byte[] home = rootFh(tony);
		assertEquals(Arrays.asList("f", "sub"), sorted(names(tony, home)), "the folder is the root");
		assertEquals(Nfs4.NFS4_OK, lookup(tony, home, "sub"));
		assertEquals(Nfs4.NFS4ERR_NOENT, lookup(tony, home, "top"));
		assertEquals(Nfs4.NFS4ERR_BADNAME, lookup(tony, home, ".."));
		// LOOKUPP stops at the root: there is nothing above it (a one-export server)
		Result up = tony.compound(new Ops().putroot().op(Nfs4.OP_LOOKUPP));
		up.ok(Nfs4.OP_PUTROOTFH);
		assertEquals(Nfs4.NFS4ERR_NOENT, up.next(Nfs4.OP_LOOKUPP));
		// from inside, LOOKUPP goes up to it and no further
		Result inside = tony.compound(new Ops().putroot().lookup("sub").op(Nfs4.OP_LOOKUPP).getfh());
		inside.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_LOOKUP).ok(Nfs4.OP_LOOKUPP).ok(Nfs4.OP_GETFH);
		assertTrue(java.util.Arrays.equals(home, inside.in.readOpaque(128)));

		// a user with no root sees everything, and its handles aren't tony's
		TestClient bob = as(1001);
		byte[] all = rootFh(bob);
		assertEquals(Arrays.asList("home", "top"), sorted(names(bob, all)));
		byte[] bobHome = lookupFh(bob, lookupFh(bob, all, "home"), "bob");
		assertEquals(Nfs4.NFS4ERR_STALE, putfh(tony, all), "the export's root is outside tony's folder");
		assertEquals(Nfs4.NFS4ERR_STALE, putfh(tony, bobHome), "so is another user's folder");
		assertEquals(Nfs4.NFS4_OK, putfh(tony, home));
		assertEquals(Nfs4.NFS4_OK, putfh(bob, home), "bob may use a handle into tony's folder");

		// a root that can't be a folder of the export leaves nothing; one that isn't there is a missing folder
		assertEquals(Nfs4.NFS4ERR_ACCESS, as(1002).compound(new Ops().putroot()).status);
		assertEquals(Nfs4.NFS4ERR_NOENT, as(1003).compound(new Ops().putroot()).status);
	}

	/** With several exports the same folder is looked for inside each. */
	@Test
	public void eachExportHasTheUsersFolder() throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource docs = mem.createFileSource("/docs");
		FileSource media = mem.createFileSource("/media");
		assertTrue(docs.mkdirs());
		assertTrue(media.mkdirs());
		file(docs, "home/tony/f", false);
		file(docs, "other", false);
		file(media, "m", false);
		start(new Export("docs", docs, false), new Export("media", media, false));

		TestClient tony = as(1000);
		byte[] pseudo = rootFh(tony);
		assertEquals(Nfs4.NFS4_OK, lookup(tony, pseudo, "docs"));
		Result in = tony.compound(new Ops().putroot().lookup("docs").getfh());
		in.ok(Nfs4.OP_PUTROOTFH).ok(Nfs4.OP_LOOKUP).ok(Nfs4.OP_GETFH);
		byte[] home = in.in.readOpaque(128);
		assertEquals(Arrays.asList("f"), names(tony, home));
		assertEquals(Nfs4.NFS4ERR_NOENT, lookup(tony, pseudo, "media"), "no such folder in media");
		// up from the folder is the folder of exports
		Result up = tony.compound(new Ops().putfh(home).op(Nfs4.OP_LOOKUPP).getfh());
		up.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_LOOKUPP).ok(Nfs4.OP_GETFH);
		assertTrue(java.util.Arrays.equals(pseudo, up.in.readOpaque(128)));
	}

	private static List<String> sorted(List<String> l) {
		List<String> c = new ArrayList<String>(l);
		java.util.Collections.sort(c);
		return c;
	}

	private static byte[] lookupFh(TestClient c, byte[] dir, String name) throws IOException {
		Result r = c.compound(new Ops().putfh(dir).lookup(name).getfh());
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_LOOKUP).ok(Nfs4.OP_GETFH);
		return r.in.readOpaque(128);
	}

	private static int putfh(TestClient c, byte[] fh) throws IOException {
		return c.compound(new Ops().putfh(fh)).status;
	}
}
