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
 */

package us.bringardner.parley.nfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsException;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/** The change attribute of a folder moves when an entry is added, removed or renamed, and only then. */
public class NfsChangeIdTest {

	private MemoryFileSourceFactory mem;
	private NfsServer server;
	private NfsClient client;

	@BeforeEach
	public void start() throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer("test.nfs.changeid", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		client = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 100, 10000);
	}

	@AfterEach
	public void stop() throws Exception {
		client.close();
		server.stop();
		assertTrue(server.awaitStopped(10000));
		mem.disConnect();
	}

	@Test
	public void aFolderThatIsNotChangedKeepsItsChangeId() throws Exception {
		client.mkdir("dir");
		long first = client.changeId("dir");
		assertEquals(first, client.changeId("dir"));
		client.info("dir");
		client.list("dir");
		assertEquals(first, client.changeId("dir"));
	}

	@Test
	public void addingRemovingAndRenamingMoveTheChangeId() throws Exception {
		client.mkdir("dir");
		long id = client.changeId("dir");

		assertTrue(client.createNew("dir/a"));
		long added = client.changeId("dir");
		assertNotEquals(id, added);

		client.rename("dir/a", "dir/b");
		long renamed = client.changeId("dir");
		assertNotEquals(added, renamed);

		client.remove("dir/b");
		assertNotEquals(renamed, client.changeId("dir"));
	}

	@Test
	public void aMissingPathIsNotFound() throws Exception {
		NfsException e = assertThrows(NfsException.class, () -> client.changeId("nothing"));
		assertTrue(e.isNotFound());
	}
}
