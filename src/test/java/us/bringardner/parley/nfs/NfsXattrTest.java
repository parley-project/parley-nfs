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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsException;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.server.Compound;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/** RFC 8276 extended attributes: GETXATTR, SETXATTR, LISTXATTRS, REMOVEXATTR and xattr_support (D101). */
public class NfsXattrTest {

	private MemoryFileSourceFactory mem;
	private NfsServer server;
	private NfsClient client;
	private String savedMinor;

	@BeforeEach
	public void start() throws Exception {
		savedMinor = System.getProperty(NfsClient.PROPERTY_MINOR_VERSION);
		System.setProperty(NfsClient.PROPERTY_MINOR_VERSION, "2");
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer("test.nfs.xattr", 0);
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

	private static byte[] b(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private void make(String path) throws Exception {
		assertTrue(client.createNew(path));
	}

	@Test
	public void theServerSaysItKeepsThem() throws Exception {
		make("a");
		assertTrue(client.isXattrSupported("a"));
		assertTrue(client.isXattrSupported("/"));
	}

	@Test
	public void setGetListRemove() throws Exception {
		make("a");
		assertEquals(Collections.emptyList(), client.listXattrs("a"));
		assertNull(client.getXattr("a", "origin"));
		assertFalse(client.removeXattr("a", "origin"));

		client.setXattr("a", "origin", b("https://example.com/a"));
		client.setXattr("a", "tag", new byte[] { 0, 1, 2, (byte) 255 });
		client.setXattr("a", "empty", new byte[0]);
		List<String> names = client.listXattrs("a");
		Collections.sort(names);
		assertEquals(List.of("empty", "origin", "tag"), names);
		assertArrayEquals(b("https://example.com/a"), client.getXattr("a", "origin"));
		assertArrayEquals(new byte[] { 0, 1, 2, (byte) 255 }, client.getXattr("a", "tag"));
		assertArrayEquals(new byte[0], client.getXattr("a", "empty"));

		client.setXattr("a", "origin", b("replaced"));
		assertArrayEquals(b("replaced"), client.getXattr("a", "origin"));
		assertEquals(3, client.listXattrs("a").size());

		assertTrue(client.removeXattr("a", "tag"));
		assertFalse(client.removeXattr("a", "tag"));
		assertNull(client.getXattr("a", "tag"));
		assertEquals(2, client.listXattrs("a").size());
	}

	@Test
	public void theyAreSeenThroughTheFileSourceToo() throws Exception {
		make("a");
		client.setXattr("a", "user.k", b("v"));
		FileSource f = mem.createFileSource("/export/a");
		java.nio.file.attribute.UserDefinedFileAttributeView v = f.getUserDefinedFileAttributeView();
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(v.size("user.k"));
		v.read("user.k", buf);
		assertArrayEquals(b("v"), buf.array());
		v.write("other", java.nio.ByteBuffer.wrap(b("w")));
		assertArrayEquals(b("w"), client.getXattr("a", "other"));
	}

	@Test
	public void createAndReplaceOptions() throws Exception {
		make("a");
		client.setXattr("a", "k", b("1"), Nfs4.SETXATTR4_CREATE);
		NfsException e = assertThrows(NfsException.class, () -> client.setXattr("a", "k", b("2"), Nfs4.SETXATTR4_CREATE));
		assertEquals(Nfs4.NFS4ERR_EXIST, e.getStatus());
		client.setXattr("a", "k", b("3"), Nfs4.SETXATTR4_REPLACE);
		assertArrayEquals(b("3"), client.getXattr("a", "k"));
		e = assertThrows(NfsException.class, () -> client.setXattr("a", "missing", b("x"), Nfs4.SETXATTR4_REPLACE));
		assertEquals(Nfs4.NFS4ERR_NOXATTR, e.getStatus());
		e = assertThrows(NfsException.class, () -> client.setXattr("a", "k", b("x"), 7));
		assertEquals(Nfs4.NFS4ERR_INVAL, e.getStatus());
	}

	@Test
	public void theChangeAttributeMovesWhenTheyChange() throws Exception {
		make("a");
		long c0 = client.changeId("a");
		client.setXattr("a", "k", b("1"));
		long c1 = client.changeId("a");
		assertTrue(c1 != c0);
		// the same value again changes nothing (RFC 8276 section 8.4.2)
		client.setXattr("a", "k", b("1"));
		assertEquals(c1, client.changeId("a"));
		client.setXattr("a", "k", b("2"));
		long c2 = client.changeId("a");
		assertTrue(c2 != c1);
		assertTrue(client.removeXattr("a", "k"));
		assertTrue(client.changeId("a") != c2);
	}

	@Test
	public void sizesAndNames() throws Exception {
		make("a");
		client.setXattr("a", "big", new byte[Compound.XATTR_VALUE_MAX]);
		assertEquals(Compound.XATTR_VALUE_MAX, client.getXattr("a", "big").length);
		NfsException e = assertThrows(NfsException.class,
				() -> client.setXattr("a", "toobig", new byte[Compound.XATTR_VALUE_MAX+1]));
		assertEquals(Nfs4.NFS4ERR_XATTR2BIG, e.getStatus());
		assertNull(client.getXattr("a", "toobig"));

		e = assertThrows(NfsException.class, () -> client.setXattr("a", "", b("x")));
		assertEquals(Nfs4.NFS4ERR_INVAL, e.getStatus());
		e = assertThrows(NfsException.class, () -> client.setXattr("a", "n".repeat(Compound.XATTR_NAME_MAX+1), b("x")));
		assertEquals(Nfs4.NFS4ERR_NAMETOOLONG, e.getStatus());

		// any text: dots, slashes, non-ASCII
		client.setXattr("a", "user.xdg.origin.url", b("1"));
		client.setXattr("a", "a/b", b("2"));
		client.setXattr("a", "café über", b("3"));
		assertArrayEquals(b("3"), client.getXattr("a", "café über"));
		assertArrayEquals(b("2"), client.getXattr("a", "a/b"));
	}

	@Test
	public void aLongListComesInPages() throws Exception {
		make("a");
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 1500; i++) {
			// 1500 names of about 40 bytes is more than one 32 KiB reply
			String name = String.format("user.attribute-with-a-longish-name-%05d", i);
			client.setXattr("a", name, new byte[] { 1 });
			expected.add(name);
		}
		List<String> got = client.listXattrs("a");
		assertEquals(1500, got.size());
		assertEquals(expected, got);
	}

	@Test
	public void foldersKeepThemToo() throws Exception {
		client.mkdir("d");
		client.setXattr("d", "k", b("v"));
		assertArrayEquals(b("v"), client.getXattr("d", "k"));
		assertEquals(List.of("k"), client.listXattrs("d"));
	}

	@Test
	public void aMissingFileIsNotFound() throws Exception {
		NfsException e = assertThrows(NfsException.class, () -> client.getXattr("nope", "k"));
		assertTrue(e.isNotFound());
		e = assertThrows(NfsException.class, () -> client.setXattr("nope", "k", b("v")));
		assertTrue(e.isNotFound());
	}

	@Test
	public void notInNfsv41() throws Exception {
		System.setProperty(NfsClient.PROPERTY_MINOR_VERSION, "1");
		NfsClient old = new NfsClient("127.0.0.1", server.getLocalPort(), "/", 1000, 1000, 10000);
		try {
			assertEquals(1, old.getMinorVersion());
			make("a");
			assertFalse(old.isXattrSupported("a"));
			NfsException e = assertThrows(NfsException.class, () -> old.getXattr("a", "k"));
			assertEquals(Nfs4.NFS4ERR_NOTSUPP, e.getStatus());
			e = assertThrows(NfsException.class, () -> old.listXattrs("a"));
			assertEquals(Nfs4.NFS4ERR_NOTSUPP, e.getStatus());
		} finally {
			old.close();
		}
	}
}
