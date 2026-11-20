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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsException;
import us.bringardner.parley.nfs.client.NfsFileInfo;
import us.bringardner.parley.nfs.client.NfsOpenFile;
import us.bringardner.parley.nfs.client.NfsSetAttributes;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/** What a FileSource needs of the client beyond its first slice (D87): attributes, SETATTR, ACCESS, links and open files. */
public class NfsClientFilesTest {

	private static final String PREFIX = "test.nfs.cfiles";

	private MemoryFileSourceFactory mem;
	private FileSource root;
	private NfsServer server;
	private final List<NfsClient> clients = new ArrayList<NfsClient>();

	private void start() throws IOException {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer(PREFIX, 0);
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
	public void infoHasOwnerGroupAndTimes() throws Exception {
		start();
		NfsClient c = client();
		c.write("f.txt", 0, bytes("data"));
		NfsFileInfo i = c.info("f.txt");
		assertNotNull(i.getOwner());
		assertNotNull(i.getGroup());
		assertTrue(i.getAccessTime() >= 0, "access time");
		assertTrue(i.getModifyTime() >= 0);
		assertTrue(i.getChangeTime() >= 0, "change time");
		for (NfsFileInfo e : c.list("/")) {
			assertNotNull(e.getOwner(), "a listing says the owner too");
		}
	}

	@Test
	public void setAttributesChangesTheModeAndTimes() throws Exception {
		start();
		NfsClient c = client();
		c.write("f.txt", 0, bytes("data"));
		c.setAttributes("f.txt", new NfsSetAttributes().mode(0600).modifyTime(1_500_000_000_000L).accessTime(1_400_000_000_000L));
		NfsFileInfo i = c.info("f.txt");
		assertEquals(0600, i.getMode() & 07777);
		assertEquals(1_500_000_000_000L, i.getModifyTime());
		assertEquals(1_400_000_000_000L, i.getAccessTime());
		c.setAttributes("f.txt", new NfsSetAttributes());   // nothing to change is no call
	}

	@Test
	public void aUserWhoIsNotTheMappedRootMayNotChangeTheOwner() throws Exception {
		start();
		NfsClient c = client();
		c.write("f.txt", 0, bytes("data"));
		NfsException e = assertThrows(NfsException.class, () -> c.setAttributes("f.txt", new NfsSetAttributes().owner("12345")));
		assertEquals(Nfs4.NFS4ERR_PERM, e.getStatus());
	}

	@Test
	public void accessSaysWhatIsAllowed() throws Exception {
		start();
		NfsClient c = client();
		c.write("f.txt", 0, bytes("data"));
		int got = c.access("f.txt", Nfs4.ACCESS4_READ | Nfs4.ACCESS4_MODIFY | Nfs4.ACCESS4_EXECUTE);
		assertTrue((got & Nfs4.ACCESS4_READ) != 0);
		assertTrue(assertThrows(NfsException.class, () -> c.access("nothing.txt", Nfs4.ACCESS4_READ)).isNotFound());
	}

	@Test
	public void aSymbolicLinkIsMadeAndRead() throws Exception {
		start();
		NfsClient c = client();
		c.write("target.txt", 0, bytes("data"));
		try {
			c.symlink("link", "target.txt");
		} catch (NfsException e) {
			// a store that can't make links says so: nothing more to test
			org.junit.jupiter.api.Assumptions.assumeTrue(false, "this store makes no symbolic links: "+e.getMessage());
		}
		assertEquals("target.txt", c.readLink("link"));
		assertTrue(c.info("link").isLink());
	}

	@Test
	public void anOpenFileReadsAndWritesAtOffsets() throws Exception {
		start();
		NfsClient c = client();
		try (NfsOpenFile f = c.openFile("f.bin", true, true)) {
			f.write(0, bytes("0123456789"), 0, 10);
			f.write(4, bytes("XY"), 0, 2);
			assertEquals("0123XY6789", new String(f.read(0, 100), StandardCharsets.UTF_8));
			assertEquals("XY", new String(f.read(4, 2), StandardCharsets.UTF_8));
			assertEquals(10, f.size());
			f.truncate(5);
			assertEquals(5, f.size());
			f.truncate(8);
			assertArrayEquals(new byte[] {'0', '1', '2', '3', 'X', 0, 0, 0}, f.read(0, 100));
			// beyond one chunk
			byte[] big = new byte[NfsClient.CHUNK*3+17];
			new java.util.Random(7).nextBytes(big);
			f.write(0, big, 0, big.length);
			assertArrayEquals(big, f.read(0, big.length));
		}
		assertEquals(NfsClient.CHUNK*3+17, c.info("f.bin").getSize());
	}

	@Test
	public void anOpenFileOutlivesARestartedServer() throws Exception {
		start();
		NfsClient c = client();
		try (NfsOpenFile f = c.openFile("f.bin", true, true)) {
			f.write(0, bytes("before"), 0, 6);
			int port = server.getLocalPort();
			server.stop();
			assertTrue(server.awaitStopped(10000));
			server = new NfsServer(PREFIX, port);
			server.setExport(new Export("test", root, false));
			server.startAndWait(10000);
			// the state of the open is gone: it is opened again by its path
			assertEquals("before", new String(f.read(0, 100), StandardCharsets.UTF_8));
			f.write(6, bytes("+after"), 0, 6);
			assertEquals("before+after", new String(f.read(0, 100), StandardCharsets.UTF_8));
		}
	}

	@Test
	public void aReadOnlyOpenOfAMissingFileIsNotFound() throws Exception {
		start();
		NfsClient c = client();
		NfsException e = assertThrows(NfsException.class, () -> c.openFile("nothing", false, false));
		assertTrue(e.isNotFound());
	}
}
