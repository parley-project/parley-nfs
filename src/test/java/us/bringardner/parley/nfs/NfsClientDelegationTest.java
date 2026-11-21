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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/** The client's read delegations and its callback server (D86) against the server over TCP. */
public class NfsClientDelegationTest {

	private static final String PREFIX = "test.nfs.cdeleg";

	private MemoryFileSourceFactory mem;
	private FileSource root;
	private NfsServer server;
	private final List<NfsClient> clients = new ArrayList<NfsClient>();
	private final List<String> properties = new ArrayList<String>();

	private void start(boolean delegations) throws IOException {
		if( delegations ) {
			System.setProperty(PREFIX+".delegations", "true");
			properties.add(PREFIX+".delegations");
		}
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer(PREFIX, 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
	}

	/** a client that takes delegations, from 127.0.0.1 */
	private NfsClient holder() throws IOException {
		NfsClient c = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 100, 10000, true);
		clients.add(c);
		// the default is NFSv4.2, where the recalls come over the session's back channel; -Dparley.nfs.client.minorVersion=0 runs 4.0
		assertEquals(Integer.getInteger(NfsClient.PROPERTY_MINOR_VERSION, 2).intValue(), c.getMinorVersion());
		return c;
	}

	/** a client from another address of this machine: the server doesn't count the holder's own host as a conflict (D65) */
	private NfsClient other(String address) throws IOException {
		NfsClient c = new NfsClient(address, server.getLocalPort(), "/", 1001, 100, 10000);
		clients.add(c);
		return c;
	}

	private static String otherAddress() throws IOException {
		for (java.net.NetworkInterface i : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
			if( i.isUp() && !i.isLoopback() ) {
				for (InetAddress a : Collections.list(i.getInetAddresses())) {
					if( a instanceof java.net.Inet4Address && !a.isLinkLocalAddress() ) {
						return a.getHostAddress();
					}
				}
			}
		}
		return null;
	}

	/**
	 * Read until the client holds a delegation: the server gives one only once it has checked the client's callback
	 * address, which it does after SETCLIENTID_CONFIRM, and the first open of an open-owner is confirmed and gets none.
	 */
	private static void readUntilDelegated(NfsClient c, String path) throws Exception {
		long end = System.currentTimeMillis()+5000;
		while( c.getDelegationCount() == 0 ) {
			assertTrue(System.currentTimeMillis() < end, "no delegation was given");
			c.readAll(path);
			Thread.sleep(50);
		}
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
		for (String p : properties) {
			System.clearProperty(p);
		}
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static String text(byte[] b) {
		return new String(b, StandardCharsets.UTF_8);
	}

	@Test
	public void aClientThatOffersNoCallbackGetsNoDelegation() throws Exception {
		start(true);
		NfsClient c = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 100, 10000);
		clients.add(c);
		c.write("f.txt", 0, bytes("data"));
		for (int i = 0; i < 4; i++) {
			assertEquals("data", text(c.readAll("f.txt")));
		}
		assertEquals(0, c.getDelegationCount());
	}

	@Test
	public void aServerThatGivesNoDelegationsLeavesThemOut() throws Exception {
		start(false);
		NfsClient c = holder();
		c.write("f.txt", 0, bytes("data"));
		for (int i = 0; i < 4; i++) {
			assertEquals("data", text(c.readAll("f.txt")));
		}
		assertEquals(0, c.getDelegationCount());
	}

	@Test
	public void aReadDelegationKeepsTheBytes() throws Exception {
		start(true);
		NfsClient c = holder();
		c.write("f.txt", 0, bytes("before"));
		readUntilDelegated(c, "f.txt");
		assertEquals("before", text(c.readAll("f.txt")));
		assertEquals(1, c.getDelegationCount());
		// the file is changed where the server doesn't see it (so nothing recalls the delegation): the kept bytes are read
		try (java.io.OutputStream out = root.getChild("f.txt").getOutputStream()) {
			out.write(bytes("behind"));
		}
		assertEquals("before", text(c.readAll("f.txt")), "kept under the delegation");
	}

	@Test
	public void aWriteOfItsOwnDropsWhatWasKept() throws Exception {
		start(true);
		NfsClient c = holder();
		c.write("f.txt", 0, bytes("one"));
		readUntilDelegated(c, "f.txt");
		c.readAll("f.txt");
		c.write("f.txt", 0, bytes("two"));
		assertEquals("two", text(c.readAll("f.txt")));
		assertEquals("two", text(c.readAll("f.txt")));
	}

	@Test
	public void anotherClientsWriteRecallsTheDelegationAndThenSucceeds() throws Exception {
		String address = otherAddress();
		Assumptions.assumeTrue(address != null, "this machine has no address but the loopback");
		start(true);
		NfsClient a = holder();
		a.write("f.txt", 0, bytes("old"));
		readUntilDelegated(a, "f.txt");
		assertEquals("old", text(a.readAll("f.txt")));
		assertEquals(1, a.getDelegationCount());

		NfsClient b = other(address);
		b.write("f.txt", 0, bytes("new"));   // waits (DELAY) for the delegation to come back

		assertEquals(0, a.getDelegationCount());
		assertEquals("new", text(a.readAll("f.txt")), "what was kept is gone");
	}

	@Test
	public void aClosedClientGivesItsDelegationsBack() throws Exception {
		String address = otherAddress();
		Assumptions.assumeTrue(address != null, "this machine has no address but the loopback");
		start(true);
		NfsClient a = holder();
		a.write("f.txt", 0, bytes("old"));
		readUntilDelegated(a, "f.txt");
		assertEquals(1, a.getDelegationCount());
		a.close();
		long start = System.currentTimeMillis();
		other(address).write("f.txt", 0, bytes("new"));
		assertTrue(System.currentTimeMillis()-start < 400, "no recall to wait for");
	}

	@Test
	public void aRestartedServerTakesTheDelegationsAway() throws Exception {
		start(true);
		NfsClient a = holder();
		a.write("f.txt", 0, bytes("old"));
		readUntilDelegated(a, "f.txt");
		assertEquals(1, a.getDelegationCount());
		int port = server.getLocalPort();
		server.stop();
		assertTrue(server.awaitStopped(10000));
		server = new NfsServer(PREFIX, port);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		assertEquals("old", text(a.readAll("f.txt")));
		assertFalse(a.getDelegationCount() > 1);
	}
}
