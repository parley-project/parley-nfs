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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsDirectoryEvent;
import us.bringardner.parley.nfs.client.NfsDirectoryListener;
import us.bringardner.parley.nfs.client.NfsDirectoryWatch;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/** Directory delegations and CB_NOTIFY (D93): a client is told of the changes others make to a folder. */
public class NfsDirectoryWatchTest {

	private static final String PREFIX = "test.nfs.dirwatch";

	private MemoryFileSourceFactory mem;
	private NfsServer server;
	private final List<NfsClient> clients = new ArrayList<NfsClient>();
	private final List<NfsDirectoryWatch> watches = new ArrayList<NfsDirectoryWatch>();

	/** collects what a listener is told */
	private static final class Collector implements NfsDirectoryListener {
		final BlockingQueue<String> told = new LinkedBlockingQueue<String>();

		@Override
		public void changed(NfsDirectoryEvent event) {
			told.add(event.toString());
		}

		@Override
		public void lost(String reason) {
			told.add("lost: "+reason);
		}

		String next() throws InterruptedException {
			return told.poll(5, TimeUnit.SECONDS);
		}
	}

	private void start(boolean delegations) throws Exception {
		if( delegations ) {
			System.setProperty(PREFIX+".delegations", "true");
		}
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer(PREFIX, 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
	}

	@AfterEach
	public void stop() throws Exception {
		System.clearProperty(PREFIX+".delegations");
		for (NfsDirectoryWatch w : watches) {
			w.close();
		}
		for (NfsClient c : clients) {
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

	private NfsClient holder() throws Exception {
		NfsClient c = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 100, 10000, true);
		clients.add(c);
		return c;
	}

	private NfsClient other() throws Exception {
		NfsClient c = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1001, 100, 10000);
		clients.add(c);
		return c;
	}

	/** the server gives a directory delegation once it has tried the client's back channel, which takes a moment */
	private NfsDirectoryWatch watch(NfsClient c, String path, NfsDirectoryListener l) throws Exception {
		long end = System.currentTimeMillis()+5000;
		while( true ) {
			NfsDirectoryWatch w = c.watchDirectory(path, l);
			if( w != null ) {
				watches.add(w);
				return w;
			}
			assertTrue(System.currentTimeMillis() < end, "no directory delegation was given");
			Thread.sleep(50);
		}
	}

	@Test
	public void changesOfAnotherClientAreToldInOrder() throws Exception {
		start(true);
		NfsClient h = holder();
		Assumptions.assumeTrue(h.getMinorVersion() >= 1, "directory delegations are NFSv4.1");
		NfsClient o = other();
		o.mkdir("dir");
		Collector told = new Collector();
		NfsDirectoryWatch w = watch(h, "dir", told);
		assertTrue(w.isActive());
		assertEquals(1, h.getWatchCount());
		o.createNew("dir/a.txt");
		o.rename("dir/a.txt", "dir/b.txt");
		o.mkdir("dir/sub");
		o.remove("dir/b.txt");
		assertEquals("ADDED dir/a.txt", told.next());
		assertEquals("RENAMED dir/a.txt -> b.txt", told.next());
		assertEquals("ADDED dir/sub", told.next());
		assertEquals("REMOVED dir/b.txt", told.next());
		assertNull(told.told.poll(200, TimeUnit.MILLISECONDS));
	}

	@Test
	public void aMoveBetweenFoldersIsARemoveAndAnAdd() throws Exception {
		start(true);
		NfsClient h = holder();
		Assumptions.assumeTrue(h.getMinorVersion() >= 1, "directory delegations are NFSv4.1");
		NfsClient o = other();
		o.mkdir("from");
		o.mkdir("to");
		o.createNew("from/f");
		Collector fromTold = new Collector();
		Collector toTold = new Collector();
		watch(h, "from", fromTold);
		watch(h, "to", toTold);
		o.rename("from/f", "to/g");
		assertEquals("REMOVED from/f", fromTold.next());
		assertEquals("ADDED to/g", toTold.next());
	}

	@Test
	public void theHoldersOwnChangesAreNotToldBack() throws Exception {
		start(true);
		NfsClient h = holder();
		Assumptions.assumeTrue(h.getMinorVersion() >= 1, "directory delegations are NFSv4.1");
		NfsClient o = other();
		h.mkdir("dir");
		Collector told = new Collector();
		watch(h, "dir", told);
		h.createNew("dir/mine");
		o.createNew("dir/theirs");
		assertEquals("ADDED dir/theirs", told.next());
		assertNull(told.told.poll(200, TimeUnit.MILLISECONDS));
	}

	@Test
	public void closingTheWatchEndsIt() throws Exception {
		start(true);
		NfsClient h = holder();
		Assumptions.assumeTrue(h.getMinorVersion() >= 1, "directory delegations are NFSv4.1");
		NfsClient o = other();
		o.mkdir("dir");
		Collector told = new Collector();
		NfsDirectoryWatch w = watch(h, "dir", told);
		int before = server.getStateManager().getDelegationCount();
		w.close();
		assertFalse(w.isActive());
		assertEquals(0, h.getWatchCount());
		assertEquals("lost: the watch was closed", told.next());
		assertEquals(before-1, server.getStateManager().getDelegationCount());
		o.createNew("dir/x");
		assertNull(told.told.poll(300, TimeUnit.MILLISECONDS));
	}

	@Test
	public void removingTheFolderTakesTheDelegationBack() throws Exception {
		start(true);
		NfsClient h = holder();
		Assumptions.assumeTrue(h.getMinorVersion() >= 1, "directory delegations are NFSv4.1");
		NfsClient o = other();
		o.mkdir("dir");
		Collector told = new Collector();
		NfsDirectoryWatch w = watch(h, "dir", told);
		o.remove("dir");
		String s = told.next();
		assertNotNull(s);
		assertTrue(s.startsWith("lost: "), s);
		assertFalse(w.isActive());
	}

	@Test
	public void aServerWithoutDelegationsGivesNone() throws Exception {
		start(false);
		NfsClient h = holder();
		Assumptions.assumeTrue(h.getMinorVersion() >= 1, "directory delegations are NFSv4.1");
		h.mkdir("dir");
		Thread.sleep(300);
		assertNull(h.watchDirectory("dir", new Collector()));
	}

	@Test
	public void aClientWithoutCallbacksGetsNone() throws Exception {
		start(true);
		NfsClient c = other();
		c.mkdir("dir");
		assertNull(c.watchDirectory("dir", new Collector()));
	}

	@Test
	public void theClientEndingTellsTheListener() throws Exception {
		start(true);
		NfsClient h = holder();
		Assumptions.assumeTrue(h.getMinorVersion() >= 1, "directory delegations are NFSv4.1");
		h.mkdir("dir");
		Collector told = new Collector();
		watch(h, "dir", told);
		h.close();
		assertEquals("lost: the client was closed", told.next());
	}
}
