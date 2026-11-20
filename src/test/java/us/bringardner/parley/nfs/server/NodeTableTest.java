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
package us.bringardner.parley.nfs.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.protocol.Nfs4;

/**
 * The node table kept in a file (decision D41) and dropped from memory when idle (D42).
 */
public class NodeTableTest {

	private MemoryFileSourceFactory mem;
	private FileSource root;
	private FileSource state;
	private Export export;

	@BeforeEach
	public void setUp() throws IOException {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		state = mem.createFileSource("/state");
		assertTrue(state.mkdirs());
		export = new Export("test", root, false);
	}

	@AfterEach
	public void tearDown() throws Exception {
		export.getNodes().close();
		mem.disConnect();
	}

	private NodeStore store() throws IOException {
		return new NodeStore(state.getChild("test.nodes"));
	}

	private Export reopen(int max) throws IOException {
		export.getNodes().close();
		export = new Export("test", root, false);
		export.getNodes().open(store(), max);
		return export;
	}

	private void touch(String path) throws IOException {
		FileSource f = root;
		String[] parts = path.split("/");
		for (int i = 0; i < parts.length; i++) {
			f = f.getChild(parts[i]);
			if( i < parts.length-1 ) {
				f.mkdirs();
			}
		}
		f.createNewFile();
	}

	@Test
	public void aRenamedFilesHandleSurvivesARestart() throws Exception {
		export.getNodes().open(store(), 100);
		touch("a");
		Node n = export.getNodes().get("a");
		byte[] handle = export.toHandle(n);
		// a is renamed to b, then a new file takes the name a
		root.getChild("a").renameTo(root.getChild("b"));
		export.getNodes().renamed("a", "b");
		touch("a");
		Node fresh = export.getNodes().get("a");
		assertNotEquals(n.getFileid(), fresh.getFileid());
		byte[] freshHandle = export.toHandle(fresh);

		reopen(100);
		// the old handle names a path that now holds the other file: the stored identity wins
		Node found = export.fromHandle(handle);
		assertEquals("b", found.getPath());
		assertEquals(n.getFileid(), found.getFileid());
		Node foundFresh = export.fromHandle(freshHandle);
		assertEquals("a", foundFresh.getPath());
		assertEquals(fresh.getFileid(), foundFresh.getFileid());
	}

	@Test
	public void aFolderRenameMovesWhatIsUnderIt() throws Exception {
		export.getNodes().open(store(), 100);
		touch("d/x");
		Node x = export.getNodes().get("d/x");
		root.getChild("d").renameTo(root.getChild("e"));
		export.getNodes().renamed("d", "e");
		reopen(100);
		assertEquals("e/x", export.getNodes().storedPath(x.getFileid()));
		assertEquals(x.getFileid(), export.getNodes().get("e/x").getFileid());
	}

	@Test
	public void aLongPathHasItsIdentityStored() throws Exception {
		export.getNodes().open(store(), 100);
		StringBuilder p = new StringBuilder("d");
		while( p.length() < 140 ) {
			p.append("/dir0123456789");
		}
		touch(p.toString());
		Node n = export.getNodes().get(p.toString());
		byte[] handle = export.toHandle(n);
		assertTrue(handle.length < p.length(), "the handle has no path");
		reopen(100);
		assertEquals(p.toString(), export.fromHandle(handle).getPath());
	}

	@Test
	public void aRemovedFileIsForgotten() throws Exception {
		export.getNodes().open(store(), 100);
		touch("a");
		Node n = export.getNodes().get("a");
		root.getChild("a").renameTo(root.getChild("b"));
		export.getNodes().renamed("a", "b");
		assertEquals(1, export.getNodes().storedSize());
		root.getChild("b").delete();
		export.getNodes().removed("b");
		assertEquals(0, export.getNodes().storedSize());
		reopen(100);
		assertNull(export.getNodes().storedPath(n.getFileid()));
	}

	@Test
	public void aStoredFileThatIsGoneIsStaleAndForgotten() throws Exception {
		export.getNodes().open(store(), 100);
		touch("a");
		Node n = export.getNodes().get("a");
		byte[] handle = export.toHandle(n);
		root.getChild("a").renameTo(root.getChild("b"));
		export.getNodes().renamed("a", "b");
		export.getNodes().close();
		// deleted behind the server's back
		root.getChild("b").delete();
		reopen(100);
		assertEquals(1, export.getNodes().storedSize());
		Nfs4Exception e = assertThrows(Nfs4Exception.class, () -> export.fromHandle(handle));
		assertEquals(Nfs4.NFS4ERR_STALE, e.getStatus());
		assertEquals(0, export.getNodes().storedSize());
	}

	@Test
	public void pruneForgetsStoredFilesThatAreGone() throws Exception {
		export.getNodes().open(store(), 100);
		touch("a");
		export.getNodes().get("a");
		root.getChild("a").renameTo(root.getChild("b"));
		export.getNodes().renamed("a", "b");
		root.getChild("b").delete();
		assertEquals(1, export.getNodes().storedSize());
		export.getNodes().pruneMissing();
		assertEquals(0, export.getNodes().storedSize());
	}

	@Test
	public void namesWithTabsNewlinesAndBackslashesSurvive() throws Exception {
		export.getNodes().open(store(), 100);
		String odd = "we\tir\nd\\na\rme";
		touch(odd);
		Node n = export.getNodes().get(odd);
		root.getChild(odd).renameTo(root.getChild("plain"));
		export.getNodes().renamed(odd, "plain");
		touch(odd);
		Node again = export.getNodes().get(odd);
		reopen(100);
		assertEquals("plain", export.getNodes().storedPath(n.getFileid()));
		assertEquals(odd, export.getNodes().storedPath(again.getFileid()));
	}

	@Test
	public void idleNodesAreDroppedAndComeBackWithTheSameFileid() throws Exception {
		export.getNodes().open(store(), 10);
		NodeTable t = export.getNodes();
		Node moved = null;
		for (int i = 0; i < 40; i++) {
			touch("f"+i);
			Node n = t.get("f"+i);
			if( i == 0 ) {
				moved = n;
			}
		}
		root.getChild("f0").renameTo(root.getChild("g0"));
		t.renamed("f0", "g0");
		long id = moved.getFileid();
		assertEquals(40, t.size());
		// nothing is idle yet
		t.trim(System.currentTimeMillis(), true);
		assertEquals(40, t.size());
		t.trim(System.currentTimeMillis()+NodeTable.IDLE_BEFORE_DROP+1000, true);
		assertTrue(t.size() <= 9, "dropped down to 90% of the limit: "+t.size());
		// the renamed file's identity is stored, so it comes back the same
		assertEquals(id, t.get("g0").getFileid());
		assertEquals(id, export.fromHandle(export.toHandle(moved)).getFileid());
	}

	@Test
	public void aNodeWithAnOpenIsNotDropped() throws Exception {
		NodeTable t = export.getNodes();
		t.setMaxNodes(2);
		for (int i = 0; i < 10; i++) {
			t.get("f"+i);
		}
		Node held = t.get("f3");
		held.opens.add(null);
		t.trim(System.currentTimeMillis()+NodeTable.IDLE_BEFORE_DROP+1000, true);
		assertEquals(held, t.findPath("f3"));
		held.opens.clear();
	}

	@Test
	public void withoutAFileOnlyNodesThatComeBackAreDropped() throws Exception {
		NodeTable t = export.getNodes();
		t.setMaxNodes(2);
		Node a = t.get("a");
		root.getChild("a").createNewFile();
		t.get("b");
		t.get("c");
		// a was renamed: its fileid isn't its path's hash, and there is no file to keep it in
		root.getChild("a").renameTo(root.getChild("z"));
		t.renamed("a", "z");
		t.trim(System.currentTimeMillis()+NodeTable.IDLE_BEFORE_DROP+1000, true);
		assertEquals(a, t.findPath("z"));
		assertNull(t.findPath("b"));
	}

	/**
	 * The renamed node is the oldest one here, so it would be dropped first if it could be (it was left to the order of
	 * equal times before): with no file its id is gone on a restart, so it stays (decision D42).
	 */
	@Test
	public void aRenamedNodeWithNoFileIsKeptWhateverItsAge() throws Exception {
		NodeTable t = export.getNodes();
		t.setMaxNodes(2);
		Node a = t.get("a");
		root.getChild("a").createNewFile();
		Thread.sleep(20);
		t.get("b");
		Thread.sleep(20);
		t.get("c");
		root.getChild("a").renameTo(root.getChild("z"));
		t.renamed("a", "z");
		t.trim(System.currentTimeMillis()+NodeTable.IDLE_BEFORE_DROP+1000, true);
		assertEquals(a, t.findPath("z"), "kept: it can't come back");
		assertNull(t.findPath("b"), "its id is its path's hash: it is made again with the same id");
		assertNull(t.findPath("c"));
	}

	@Test
	public void aTornLastLineIsIgnored() throws Exception {
		export.getNodes().open(store(), 100);
		touch("a");
		export.getNodes().get("a");
		root.getChild("a").renameTo(root.getChild("b"));
		export.getNodes().renamed("a", "b");
		export.getNodes().close();
		try (java.io.OutputStream o = state.getChild("test.nodes").getOutputStream(true)) {
			o.write("A\tzz".getBytes("UTF-8"));
		}
		reopen(100);
		assertEquals(1, export.getNodes().storedSize());
	}
}
