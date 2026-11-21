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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsException;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/** The NFSv4.2 operations: SEEK, ALLOCATE, DEALLOCATE and COPY (D92). */
public class NfsV42Test {

	private MemoryFileSourceFactory mem;
	private NfsServer server;
	private NfsClient client;
	private String savedMinor;

	@BeforeEach
	public void start() throws Exception {
		// these need 4.2 whatever the run's default is
		savedMinor = System.getProperty(NfsClient.PROPERTY_MINOR_VERSION);
		System.setProperty(NfsClient.PROPERTY_MINOR_VERSION, "2");
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer("test.nfs.v42", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		client = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 1000, 10000);
	}

	@AfterEach
	public void stop() throws Exception {
		if( savedMinor == null ) {
			System.clearProperty(NfsClient.PROPERTY_MINOR_VERSION);
		} else {
			System.setProperty(NfsClient.PROPERTY_MINOR_VERSION, savedMinor);
		}
		client.close();
		server.stop();
		assertTrue(server.awaitStopped(10000));
		mem.disConnect();
	}

	private void make(String path, String text) throws Exception {
		assertTrue(client.createNew(path));
		client.write(path, 0, text.getBytes(StandardCharsets.UTF_8));
	}

	private String text(String path) throws Exception {
		return new String(client.readAll(path), StandardCharsets.UTF_8);
	}

	@Test
	public void theClientIsOn42() throws Exception {
		assertEquals(2, client.getMinorVersion());
		assertTrue(client.supportsV42());
	}

	@Test
	public void seekFindsDataAndTheHoleAtTheEnd() throws Exception {
		make("a", "0123456789");
		assertEquals(3, client.seek("a", 3, false));
		assertEquals(10, client.seek("a", 3, true));
		assertEquals(-1, client.seek("a", 10, false));
		assertEquals(-1, client.seek("a", 99, true));
	}

	@Test
	public void allocateMakesTheFileLongerWithZeros() throws Exception {
		make("a", "abc");
		client.allocate("a", 0, 2);
		assertEquals("abc", text("a"));
		client.allocate("a", 2, 6);
		byte[] all = client.readAll("a");
		assertEquals(8, all.length);
		assertArrayEquals(new byte[] {'a', 'b', 'c', 0, 0, 0, 0, 0}, all);
	}

	@Test
	public void deallocateZerosARangeAndKeepsTheLength() throws Exception {
		make("a", "0123456789");
		client.deallocate("a", 2, 3);
		assertArrayEquals(new byte[] {'0', '1', 0, 0, 0, '5', '6', '7', '8', '9'}, client.readAll("a"));
		// a range past the end stops at the end
		client.deallocate("a", 8, 100);
		assertEquals(10, client.readAll("a").length);
		assertEquals(0, client.readAll("a")[9]);
	}

	@Test
	public void copyWithinTheServer() throws Exception {
		make("src", "hello world");
		assertTrue(client.createNew("dst"));
		assertEquals(5, client.copy("src", 6, "dst", 0, 5));
		assertEquals("world", text("dst"));
		// 0 copies to the end of the source
		assertEquals(11, client.copy("src", 0, "dst", 0, 0));
		assertEquals("hello world", text("dst"));
	}

	@Test
	public void copyInOneFileMayNotOverlap() throws Exception {
		make("a", "0123456789");
		NfsException e = assertThrows(NfsException.class, () -> client.copy("a", 0, "a", 3, 5));
		assertEquals(Nfs4.NFS4ERR_INVAL, e.getStatus());
		assertEquals(5, client.copy("a", 0, "a", 5, 5));
		assertEquals("0123401234", text("a"));
	}

	@Test
	public void copyFromPastTheEndIsInvalid() throws Exception {
		make("a", "abc");
		assertTrue(client.createNew("b"));
		NfsException e = assertThrows(NfsException.class, () -> client.copy("a", 7, "b", 0, 2));
		assertEquals(Nfs4.NFS4ERR_INVAL, e.getStatus());
	}

	@Test
	public void aClientOf41CanNotUseThem() throws Exception {
		client.close();
		System.setProperty(NfsClient.PROPERTY_MINOR_VERSION, "1");
		try {
			client = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 1000, 10000);
		} finally {
			System.setProperty(NfsClient.PROPERTY_MINOR_VERSION, "2");
		}
		assertEquals(1, client.getMinorVersion());
		make("a", "abc");
		NfsException e = assertThrows(NfsException.class, () -> client.seek("a", 0, false));
		assertEquals(Nfs4.NFS4ERR_NOTSUPP, e.getStatus());
	}
}
