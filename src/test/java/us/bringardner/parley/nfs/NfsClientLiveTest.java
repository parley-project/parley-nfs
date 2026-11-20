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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsException;
import us.bringardner.parley.nfs.client.NfsFileInfo;

/**
 * The generic client (D77) against a real NFS server, such as the Linux kernel's in a VM (tools/client-live-test.sh
 * sets one up and runs this). Skipped unless nfs.live.host is set.
 * <p>
 * System properties: nfs.live.host (required), nfs.live.port (2049), nfs.live.mount (/), nfs.live.uid (1000) and
 * nfs.live.gid (1000), the user the client says it is (AUTH_SYS), who must be able to write the export.
 * Everything happens in a folder of its own, which is removed at the end.
 */
public class NfsClientLiveTest {

	private NfsClient client;
	private String work;

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@BeforeEach
	public void connect() throws Exception {
		String host = System.getProperty("nfs.live.host");
		Assumptions.assumeTrue(host != null && !host.isEmpty(), "no nfs.live.host: not testing against a real server");
		client = new NfsClient(host, Integer.getInteger("nfs.live.port", 2049), System.getProperty("nfs.live.mount", "/"),
				Long.getLong("nfs.live.uid", 1000L), Long.getLong("nfs.live.gid", 1000L), 20000);
		work = "client-live-" + System.currentTimeMillis();
		client.mkdir(work);
	}

	@AfterEach
	public void disconnect() throws Exception {
		if( client != null ) {
			try {
				if( work != null && client.exists(work) ) {
					removeAll(work);
				}
			} finally {
				client.close();
			}
		}
	}

	private void removeAll(String path) throws IOException {
		NfsFileInfo info = client.info(path);
		if( info.isDirectory() ) {
			for (NfsFileInfo kid : client.list(path)) {
				removeAll(path + "/" + kid.getName());
			}
		}
		client.remove(path);
	}

	@Test
	public void theServerGaveALease() throws Exception {
		assertTrue(client.getLeaseSeconds() > 0, "lease " + client.getLeaseSeconds());
		assertTrue(client.info("/").isDirectory());
	}

	@Test
	public void filesAndFolders() throws Exception {
		String f = work + "/hello.txt";
		assertFalse(client.exists(f));
		client.write(f, 0, bytes("hello world"));
		assertEquals("hello world", new String(client.readAll(f), StandardCharsets.UTF_8));
		NfsFileInfo info = client.info(f);
		assertTrue(info.isFile());
		assertEquals(11, info.getSize());
		assertTrue(info.getModifyTime() > 0);

		client.write(f, 6, bytes("NFS!!"));
		assertEquals("hello NFS!!", new String(client.readAll(f), StandardCharsets.UTF_8));
		assertEquals("NFS", new String(client.read(f, 6, 3), StandardCharsets.UTF_8));
		assertEquals(0, client.read(f, 11, 10).length);

		client.mkdir(work + "/docs");
		client.mkdir(work + "/docs/inner");
		client.write(work + "/docs/inner/a.txt", 0, bytes("a"));
		client.write(work + "/docs/b.txt", 0, bytes("bb"));
		List<String> names = new ArrayList<String>();
		for (NfsFileInfo i : client.list(work + "/docs")) {
			names.add(i.getName() + (i.isDirectory() ? "/" : ":" + i.getSize()));
		}
		Collections.sort(names);
		assertEquals(List.of("b.txt:2", "inner/"), names);

		NfsException e = assertThrows(NfsException.class, () -> client.remove(work + "/docs"));
		assertTrue(e.getMessage() != null);
		removeAll(work + "/docs");
		assertFalse(client.exists(work + "/docs"));
	}

	@Test
	public void aMissingFileIsNotFound() throws Exception {
		assertTrue(assertThrows(NfsException.class, () -> client.info(work + "/nothing")).isNotFound());
		assertTrue(assertThrows(NfsException.class, () -> client.readAll(work + "/nothing")).isNotFound());
		assertTrue(assertThrows(NfsException.class, () -> client.list(work + "/nothing")).isNotFound());
		assertTrue(assertThrows(NfsException.class, () -> client.remove(work + "/nothing")).isNotFound());
	}

	/** Several calls' worth of bytes, both ways. */
	@Test
	public void bigFiles() throws Exception {
		byte[] data = new byte[NfsClient.CHUNK * 8 + 777];
		new Random(7).nextBytes(data);
		client.write(work + "/big.bin", 0, data);
		assertEquals(data.length, client.info(work + "/big.bin").getSize());
		assertArrayEquals(data, client.readAll(work + "/big.bin"));
		assertArrayEquals(java.util.Arrays.copyOfRange(data, NfsClient.CHUNK - 5, NfsClient.CHUNK + 5),
				client.read(work + "/big.bin", NfsClient.CHUNK - 5, 10));
	}

	/** A listing too long for one READDIR reply. */
	@Test
	public void aLongListing() throws Exception {
		int count = 400;
		client.mkdir(work + "/many");
		for (int i = 0; i < count; i++) {
			client.write(work + "/many/a-fairly-long-file-name-number-" + i + ".txt", 0, bytes("" + i));
		}
		List<String> names = new ArrayList<String>();
		for (NfsFileInfo i : client.list(work + "/many")) {
			names.add(i.getName());
		}
		assertEquals(count, names.size());
		assertEquals(count, new java.util.HashSet<String>(names).size(), "no name twice");
	}
}
