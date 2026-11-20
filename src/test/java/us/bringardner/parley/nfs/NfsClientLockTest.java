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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsLock;
import us.bringardner.parley.nfs.client.NfsLockDeniedException;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/** The client's byte range locks (D85) against the server over TCP. */
public class NfsClientLockTest {

	private MemoryFileSourceFactory mem;
	private FileSource root;
	private NfsServer server;
	private final List<NfsClient> clients = new ArrayList<NfsClient>();

	private void start(String prefix) throws IOException {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer(prefix, 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
	}

	private NfsClient client() throws IOException {
		NfsClient c = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 100, 10000);
		clients.add(c);
		return c;
	}

	@AfterEach
	public void tearDown() throws Exception {
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

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void aLockKeepsAnotherClientOutUntilItIsUnlocked() throws Exception {
		start("test.nfs.lock.basic");
		NfsClient a = client();
		NfsClient b = client();
		a.write("f.txt", 0, bytes("0123456789"));

		NfsLock mine = a.lock("f.txt", 0, 5, true);
		assertTrue(mine.isHeld());
		NfsLockDeniedException denied = assertThrows(NfsLockDeniedException.class, () -> b.lock("f.txt", 3, 4, false));
		assertEquals(0, denied.getOffset());
		assertEquals(5, denied.getLength());
		assertTrue(denied.isWrite());
		// outside the range it is free
		b.lock("f.txt", 5, 5, true).unlock();

		mine.unlock();
		assertFalse(mine.isHeld());
		mine.unlock();   // again is nothing
		b.lock("f.txt", 0, 5, true).unlock();
	}

	@Test
	public void readLocksShareAndAWriteLockDoesNot() throws Exception {
		start("test.nfs.lock.shared");
		NfsClient a = client();
		NfsClient b = client();
		a.write("f.txt", 0, bytes("data"));
		try (NfsLock r1 = a.lock("f.txt", 0, NfsLock.TO_END, false); NfsLock r2 = b.lock("f.txt", 0, 2, false)) {
			assertTrue(r1.isHeld() && r2.isHeld());
			assertThrows(NfsLockDeniedException.class, () -> b.lock("f.txt", 1, 1, true));
		}
		try (NfsLock w = a.lock("f.txt", 0, 1, true)) {
			assertTrue(w.isWrite());
		}
	}

	@Test
	public void testLockSaysWhatWouldConflict() throws Exception {
		start("test.nfs.lock.test");
		NfsClient a = client();
		NfsClient b = client();
		a.write("f.txt", 0, bytes("data"));
		assertNull(b.testLock("f.txt", 0, 4, true));
		try (NfsLock l = a.lock("f.txt", 2, 2, true)) {
			NfsLockDeniedException d = b.testLock("f.txt", 0, 4, false);
			assertNotNull(d);
			assertEquals(2, d.getOffset());
			assertEquals(2, d.getLength());
			// a lock belongs to its lock-owner, not to its client: the test's owner is another one
			assertNotNull(a.testLock("f.txt", 0, 4, true));
		}
		assertNull(b.testLock("f.txt", 0, 4, true));
	}

	@Test
	public void aLockedFileCanStillBeReadAndWrittenByItsHolder() throws Exception {
		start("test.nfs.lock.io");
		NfsClient a = client();
		a.write("f.txt", 0, bytes("hello"));
		try (NfsLock l = a.lock("f.txt", 0, NfsLock.TO_END, true)) {
			a.write("f.txt", 0, bytes("HELLO"));
			assertEquals("HELLO", new String(a.readAll("f.txt"), StandardCharsets.UTF_8));
		}
	}

	@Test
	public void aServerThatRestartedWithoutAGracePeriodLosesTheLockAndTellsTheCaller() throws Exception {
		String prefix = "test.nfs.lock.restart";
		start(prefix);
		NfsClient a = client();
		a.write("f.txt", 0, bytes("data"));
		List<NfsLock> lost = new CopyOnWriteArrayList<NfsLock>();
		a.setLockLostHandler(lost::add);
		NfsLock l = a.lock("f.txt", 0, 4, true);

		int port = server.getLocalPort();
		server.stop();
		assertTrue(server.awaitStopped(10000));
		server = new NfsServer(prefix, port);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);

		// the next call makes the connection again and tries to reclaim; this server has no grace period for locks (D21)
		assertEquals("data", new String(a.readAll("f.txt"), StandardCharsets.UTF_8));
		assertFalse(l.isHeld());
		assertNotNull(l.getLostReason());
		assertEquals(1, lost.size());
		assertEquals(l, lost.get(0));
		l.unlock();   // nothing to give up
		// and the range is free for anyone
		NfsClient b = client();
		b.lock("f.txt", 0, 4, true).unlock();
	}
}
