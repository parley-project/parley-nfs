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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsException;
import us.bringardner.parley.nfs.client.NfsFileInfo;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/**
 * The generic client (D77) against the server over TCP.
 */
public class NfsClientTest {

	private MemoryFileSourceFactory mem;
	private FileSource root;
	private NfsServer server;
	private final List<NfsClient> clients = new ArrayList<NfsClient>();

	private NfsServer start(String prefix, FileSource folder) throws IOException {
		server = new NfsServer(prefix, 0);
		server.setExport(new Export("test", folder, false));
		server.startAndWait(10000);
		return server;
	}

	private NfsServer startMemory(String prefix) throws IOException {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		return start(prefix, root);
	}

	private NfsClient client(String mount) throws IOException {
		NfsClient c = new NfsClient("127.0.0.1", server.getLocalPort(), mount, 1000, 100, 10000);
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
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void filesAndFolders() throws Exception {
		startMemory("test.nfs.client.basic");
		NfsClient c = client("/");
		assertEquals(90, c.getLeaseSeconds());

		NfsFileInfo top = c.info("/");
		assertTrue(top.isDirectory());
		assertFalse(c.exists("hello.txt"));

		c.write("hello.txt", 0, bytes("hello world"));
		assertTrue(c.exists("/hello.txt"));
		assertEquals("hello world", new String(c.readAll("hello.txt"), StandardCharsets.UTF_8));
		assertEquals("hello world", new String(root.getChild("hello.txt").getInputStream().readAllBytes(), StandardCharsets.UTF_8));
		NfsFileInfo info = c.info("hello.txt");
		assertTrue(info.isFile());
		assertEquals(11, info.getSize());
		assertTrue(info.getFileId() != -1);
		assertTrue(info.getModifyTime() > 0);
		assertTrue(info.getHandle().length > 0);

		// a write in the middle and one past the end
		c.write("hello.txt", 6, bytes("NFS!!"));
		assertEquals("hello NFS!!", new String(c.readAll("hello.txt"), StandardCharsets.UTF_8));
		assertEquals("NFS", new String(c.read("hello.txt", 6, 3), StandardCharsets.UTF_8));
		assertEquals(0, c.read("hello.txt", 11, 10).length);

		c.mkdir("docs");
		c.mkdir("docs/inner");
		c.write("docs/inner/a.txt", 0, bytes("a"));
		c.write("docs/b.txt", 0, bytes("bb"));
		assertTrue(c.info("docs").isDirectory());
		assertTrue(c.info("docs/inner").isDirectory());

		List<String> names = new ArrayList<String>();
		for (NfsFileInfo i : c.list("docs")) {
			names.add(i.getName()+(i.isDirectory() ? "/" : ":"+i.getSize()));
		}
		java.util.Collections.sort(names);
		assertEquals(List.of("b.txt:2", "inner/"), names);

		NfsException e = assertThrows(NfsException.class, () -> c.remove("docs"));
		assertEquals(Nfs4.NFS4ERR_NOTEMPTY, e.getStatus());
		c.remove("docs/inner/a.txt");
		c.remove("docs/inner");
		c.remove("docs/b.txt");
		c.remove("docs");
		assertFalse(c.exists("docs"));
		assertFalse(root.getChild("docs").exists());
	}

	@Test
	public void aMissingFileIsNotFound() throws Exception {
		startMemory("test.nfs.client.missing");
		NfsClient c = client("/");
		NfsException e = assertThrows(NfsException.class, () -> c.info("nothing"));
		assertTrue(e.isNotFound());
		e = assertThrows(NfsException.class, () -> c.readAll("nothing"));
		assertTrue(e.isNotFound());
		e = assertThrows(NfsException.class, () -> c.list("nothing"));
		assertTrue(e.isNotFound());
		e = assertThrows(NfsException.class, () -> c.remove("nothing"));
		assertTrue(e.isNotFound());
		assertThrows(IllegalArgumentException.class, () -> c.info("a/../b"));
	}

	/** More than one call's worth of bytes, both ways. */
	@Test
	public void bigFiles() throws Exception {
		startMemory("test.nfs.client.big");
		NfsClient c = client("/");
		byte[] data = new byte[NfsClient.CHUNK*2+777];
		new Random(7).nextBytes(data);
		c.write("big.bin", 0, data);
		assertEquals(data.length, c.info("big.bin").getSize());
		assertArrayEquals(data, c.readAll("big.bin"));
		byte[] part = c.read("big.bin", NfsClient.CHUNK-5, 10);
		assertArrayEquals(java.util.Arrays.copyOfRange(data, NfsClient.CHUNK-5, NfsClient.CHUNK+5), part);
	}

	/** A client mounts a folder, and then it sees that folder as its root. */
	@Test
	public void aMountedFolderIsTheRoot() throws Exception {
		startMemory("test.nfs.client.mount");
		assertTrue(root.getChild("a").mkdirs());
		root.getChild("a").getChild("x.txt").createNewFile();
		NfsClient c = client("/a");
		assertTrue(c.exists("x.txt"));
		assertFalse(c.exists("a"));
		c.write("y.txt", 0, bytes("y"));
		assertTrue(root.getChild("a").getChild("y.txt").exists());
		assertThrows(NfsException.class, () -> new NfsClient("127.0.0.1", server.getLocalPort(), "/no/such", 1000, 100, 10000));
	}

	/** The same on a local folder, which is where the server's own files are real. */
	@Test
	public void aLocalFolder(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("sub"));
		Files.write(tmp.resolve("sub").resolve("f.txt"), bytes("on disk"));
		FileSource local = new us.bringardner.parley.files.fileproxy.FileProxyFactory().createFileSource(tmp.toString());
		start("test.nfs.client.local", local);
		NfsClient c = client("/");
		assertEquals("on disk", new String(c.readAll("sub/f.txt"), StandardCharsets.UTF_8));
		c.write("sub/g.txt", 0, bytes("new"));
		assertEquals("new", new String(Files.readAllBytes(tmp.resolve("sub").resolve("g.txt")), StandardCharsets.UTF_8));
		NfsFileInfo info = c.info("sub/f.txt");
		assertEquals(7, info.getSize());
		assertTrue(info.getMode() >= 0);
		c.remove("sub/g.txt");
		assertFalse(Files.exists(tmp.resolve("sub").resolve("g.txt")));
	}

	/** A closed client is closed, and closing twice is harmless. */
	@Test
	public void closing() throws Exception {
		startMemory("test.nfs.client.close");
		NfsClient c = client("/");
		c.close();
		c.close();
		assertThrows(IOException.class, () -> c.info("/"));
	}
}
