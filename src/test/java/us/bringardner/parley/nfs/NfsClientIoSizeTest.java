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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsOpenFile;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/**
 * The sizes of one READ and one WRITE are the server's maxread and maxwrite (up to 1 MiB), and a write of several
 * pieces is sent unstable and made stable by one COMMIT.
 */
public class NfsClientIoSizeTest {

	private static final String NAME = "test.nfs.iosize";

	private MemoryFileSourceFactory mem;
	private NfsServer server;
	private NfsClient client;

	private void start(String ioSize) throws Exception {
		if( ioSize != null ) {
			System.setProperty(NAME+".ioSize", ioSize);
		}
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer(NAME, 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		client = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 1000, 10000);
	}

	@AfterEach
	public void stop() throws Exception {
		System.clearProperty(NAME+".ioSize");
		if( client != null ) {
			client.close();
		}
		if( server != null ) {
			server.stop();
			assertTrue(server.awaitStopped(10000));
		}
		if( mem != null ) {
			mem.disConnect();
		}
	}

	private static byte[] data(int n) {
		byte[] b = new byte[n];
		new Random(n).nextBytes(b);
		return b;
	}

	@Test
	public void theSizesAreTheServersUpToOneMiB() throws Exception {
		start(null);
		assertEquals(1024*1024, client.getMaxRead());
		assertEquals(1024*1024, client.getMaxWrite());
	}

	@Test
	public void aSmallServerLimitIsObeyed() throws Exception {
		start("8192");
		assertEquals(8192, client.getMaxRead());
		assertEquals(8192, client.getMaxWrite());
		// 100000 bytes are thirteen pieces of 8192: written unstable and committed, and read back in as many READs
		byte[] d = data(100000);
		assertTrue(client.createNew("small"));
		client.write("small", 0, d);
		assertArrayEquals(d, client.readAll("small"));
		try (NfsOpenFile f = client.openFile("small", false, false)) {
			assertArrayEquals(java.util.Arrays.copyOfRange(d, 5000, 60000), f.read(5000, 55000));
		}
	}

	@Test
	public void aWriteOfSeveralPiecesIsStableAndReadsBack() throws Exception {
		start("16384");
		byte[] d = data(16384*5+123);
		assertTrue(client.createNew("big"));
		try (NfsOpenFile f = client.openFile("big", true, false)) {
			f.write(0, d, 0, d.length);
			assertEquals(d.length, f.size());
		}
		assertArrayEquals(d, client.readAll("big"));
		// a piece in the middle, partly over what is there
		byte[] patch = data(16384*3);
		client.write("big", 10000, patch);
		byte[] want = d.clone();
		System.arraycopy(patch, 0, want, 10000, patch.length);
		assertArrayEquals(want, client.readAll("big"));
	}

	@Test
	public void threeMiBGoesInThreePieces() throws Exception {
		start(null);
		byte[] d = data(3*1024*1024+17);
		assertTrue(client.createNew("three"));
		client.write("three", 0, d);
		assertArrayEquals(d, client.readAll("three"));
	}
}
