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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsKerberos;
import us.bringardner.parley.nfs.client.NfsKerberos.Protection;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/** The client's Kerberos login (D84) against our own server and a Kerby KDC, in each of krb5, krb5i and krb5p. */
public class NfsClientGssTest {

	private static final String SERVER = TestKdc.SERVER;
	private static final String ALICE = "alice@"+TestKdc.REALM;

	private static final Map<String, File> keytabs = TestKdc.KEYTABS;

	private MemoryFileSourceFactory mem;
	private FileSource root;
	private NfsServer server;
	private NfsClient client;
	private NfsKerberos kerberos;
	private final java.util.List<String> properties = new java.util.ArrayList<String>();

	@BeforeAll
	public static void startKdc() throws Exception {
		TestKdc.start();
	}

	@AfterEach
	public void tearDown() throws Exception {
		if( client != null ) {
			client.close();
			client = null;
		}
		if( kerberos != null ) {
			kerberos.close();
			kerberos = null;
		}
		if( server != null ) {
			server.stop();
			assertTrue(server.awaitStopped(10000));
		}
		if( mem != null ) {
			mem.disConnect();
		}
		for (String p : properties) {
			System.clearProperty(p);
		}
	}

	private void property(String name, String value) {
		System.setProperty(name, value);
		properties.add(name);
	}

	private void start() throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		property("test.nfs.cgss.sec", "krb5,krb5i,krb5p");
		property("test.nfs.cgss.krb5.principal", SERVER);
		property("test.nfs.cgss.krb5.keytab", keytabs.get(SERVER).getAbsolutePath());
		server = new NfsServer("test.nfs.cgss", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
	}

	private NfsClient connect(Protection p) throws Exception {
		kerberos = NfsKerberos.withKeytab(SERVER, ALICE, keytabs.get(ALICE), p);
		client = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 10000, kerberos);
		return client;
	}

	private void roundTrip(Protection p) throws Exception {
		start();
		NfsClient c = connect(p);
		c.mkdir("dir");
		byte[] data = new byte[100_000];
		new Random(p.ordinal()).nextBytes(data);
		c.write("dir/file.bin", 0, data);
		assertEquals(data.length, c.info("dir/file.bin").getSize());
		assertArrayEqualsBytes(data, c.readAll("dir/file.bin"));
		assertEquals(1, c.list("dir").size());
		c.rename("dir/file.bin", "dir/other.bin");
		c.remove("dir/other.bin");
		assertFalse(c.exists("dir/other.bin"));
	}

	private static void assertArrayEqualsBytes(byte[] a, byte[] b) {
		org.junit.jupiter.api.Assertions.assertArrayEquals(a, b);
	}

	@Test
	public void krb5() throws Exception {
		roundTrip(Protection.AUTHENTICATION);
	}

	@Test
	public void krb5i() throws Exception {
		roundTrip(Protection.INTEGRITY);
	}

	@Test
	public void krb5p() throws Exception {
		roundTrip(Protection.PRIVACY);
	}

	@Test
	public void aRestartedServerGetsANewContext() throws Exception {
		start();
		NfsClient c = connect(Protection.PRIVACY);
		c.write("a.txt", 0, "before".getBytes(StandardCharsets.UTF_8));
		int port = server.getLocalPort();
		server.stop();
		assertTrue(server.awaitStopped(10000));
		server = new NfsServer("test.nfs.cgss", port);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		assertEquals("before", new String(c.readAll("a.txt"), StandardCharsets.UTF_8));
	}

	@Test
	public void aServerThatDoesNotOfferKerberosRefusesTheLogin() throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer("test.nfs.cgss.none", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		kerberos = NfsKerberos.withKeytab(SERVER, ALICE, keytabs.get(ALICE), Protection.INTEGRITY);
		NfsKerberos k = kerberos;
		assertThrows(IOException.class, () -> new NfsClient("127.0.0.1", server.getLocalPort(), "/", 5000, k));
	}
}
