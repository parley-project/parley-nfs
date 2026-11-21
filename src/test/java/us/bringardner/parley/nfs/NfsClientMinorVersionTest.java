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

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsFileInfo;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/** The client goes on with the highest minor version both sides have (D91). */
public class NfsClientMinorVersionTest {

	private MemoryFileSourceFactory mem;
	private NfsServer server;
	private NfsClient client;

	private void start(String setting) throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		if( setting != null ) {
			System.setProperty("test.nfs.minor.minorVersion", setting);
		}
		server = new NfsServer("test.nfs.minor", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
	}

	@AfterEach
	public void stop() throws Exception {
		System.clearProperty("test.nfs.minor.minorVersion");
		System.clearProperty(NfsClient.PROPERTY_MINOR_VERSION);
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

	private void work() throws Exception {
		client.mkdir("made");
		assertTrue(client.createNew("made/a.txt"));
		client.write("made/a.txt", 0, "hello".getBytes(StandardCharsets.UTF_8));
		NfsFileInfo info = client.info("made/a.txt");
		assertEquals(5, info.getSize());
		assertEquals("hello", new String(client.readAll("made/a.txt"), StandardCharsets.UTF_8));
	}

	@Test
	public void bothHave42() throws Exception {
		start(null);
		client = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 1000, 10000);
		assertEquals(2, client.getMinorVersion());
		work();
	}

	@Test
	public void aServerOf41GetsAClientOf41() throws Exception {
		start("1");
		client = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 1000, 10000);
		assertEquals(1, client.getMinorVersion());
		work();
	}

	@Test
	public void aServerOf40GetsAClientOf40() throws Exception {
		start("0");
		client = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 1000, 10000);
		assertEquals(0, client.getMinorVersion());
		work();
	}

	@Test
	public void aClientCanBeHeldTo40() throws Exception {
		start(null);
		System.setProperty(NfsClient.PROPERTY_MINOR_VERSION, "0");
		client = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 1000, 10000);
		assertEquals(0, client.getMinorVersion());
		work();
	}
}
