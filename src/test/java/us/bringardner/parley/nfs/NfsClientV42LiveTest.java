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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsDirectoryEvent;
import us.bringardner.parley.nfs.client.NfsDirectoryListener;
import us.bringardner.parley.nfs.client.NfsDirectoryWatch;
import us.bringardner.parley.nfs.client.NfsFileInfo;

/**
 * The NFSv4.2 operations (D92) against a real server. Skipped unless nfs.live.host is set, and unless the server
 * answers 4.2. System properties: nfs.live.host, nfs.live.port (2049), nfs.live.mount (/), nfs.live.uid and
 * nfs.live.gid (1000, 1000: AUTH_SYS). Everything happens in a folder of its own, which is removed at the end.
 */
public class NfsClientV42LiveTest {

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
		Assumptions.assumeTrue(client.supportsV42(), "the server doesn't answer NFSv4.2 (minor version "+client.getMinorVersion()+")");
		work = "client-live42-" + System.currentTimeMillis();
		client.mkdir(work);
	}

	@AfterEach
	public void disconnect() throws Exception {
		if( client != null ) {
			try {
				if( work != null && client.exists(work) ) {
					for (NfsFileInfo kid : client.list(work)) {
						client.remove(work + "/" + kid.getName());
					}
					client.remove(work);
				}
			} finally {
				client.close();
			}
		}
	}

	private String make(String name, String text) throws IOException {
		String p = work + "/" + name;
		client.createNew(p);
		client.write(p, 0, bytes(text));
		return p;
	}

	@Test
	public void seekFindsDataAndTheHoleAtTheEnd() throws Exception {
		String p = make("seek", "0123456789");
		assertEquals(3, client.seek(p, 3, false));
		assertEquals(10, client.seek(p, 3, true));
		assertEquals(-1, client.seek(p, 10, false));
	}

	@Test
	public void deallocateZerosARangeAndAllocateLengthens() throws Exception {
		String p = make("range", "0123456789");
		client.deallocate(p, 2, 3);
		assertArrayEquals(new byte[] {'0', '1', 0, 0, 0, '5', '6', '7', '8', '9'}, client.readAll(p));
		client.allocate(p, 10, 6);
		assertEquals(16, client.readAll(p).length);
	}

	@Test
	public void copyInsideTheServer() throws Exception {
		String src = make("src", "hello world");
		String dst = work + "/dst";
		client.createNew(dst);
		assertEquals(11, client.copy(src, 0, dst, 0, 0));
		assertEquals("hello world", new String(client.readAll(dst), StandardCharsets.UTF_8));
		assertEquals(5, client.copy(src, 6, dst, 0, 5));
		assertEquals("world world", new String(client.readAll(dst), StandardCharsets.UTF_8));
	}

	/**
	 * A directory delegation (D93). A server that doesn't give one (Linux nfsd may not) makes this pass with the client
	 * told to poll; one that does must tell of another client's change.
	 */
	@Test
	public void aDirectoryDelegationIsGivenOrRefusedCleanly() throws Exception {
		java.util.concurrent.BlockingQueue<String> told = new java.util.concurrent.LinkedBlockingQueue<String>();
		try (NfsClient holder = new NfsClient(System.getProperty("nfs.live.host"), Integer.getInteger("nfs.live.port", 2049),
				System.getProperty("nfs.live.mount", "/"), Long.getLong("nfs.live.uid", 1000L), Long.getLong("nfs.live.gid", 1000L), 20000, true)) {
			NfsDirectoryWatch w = null;
			long end = System.currentTimeMillis()+5000;
			while( w == null && System.currentTimeMillis() < end ) {
				w = holder.watchDirectory(work, new NfsDirectoryListener() {
					@Override
					public void changed(NfsDirectoryEvent event) {
						told.add(event.toString());
					}

					@Override
					public void lost(String reason) {
						told.add("lost: "+reason);
					}
				});
				if( w == null ) {
					Thread.sleep(200);
				}
			}
			System.out.println("directory delegation on "+System.getProperty("nfs.live.host")+": "+(w == null ? "not given" : "given"));
			if( w != null ) {
				make("watched", "x");
				String e = told.poll(10, java.util.concurrent.TimeUnit.SECONDS);
				assertEquals("ADDED "+work+"/watched", e);
				w.close();
			}
		}
	}
}
