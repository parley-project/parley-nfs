/*
 * Copyright 2025 Tony Bringardner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package us.bringardner.parley.nfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceUser;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.server.Attributes;
import us.bringardner.parley.nfs.server.Nfs4Exception;

/**
 * What the server answers when the file system under an export can't change an owner or a group (decision D74: a
 * provider that can't answers NFS4ERR_PERM, whatever its way of saying so): a file system the server's own user
 * doesn't own the files of (D79), and a provider that refuses with an exception.
 */
public class ChownProviderTest {

	private MemoryFileSourceFactory mem;

	@AfterEach
	public void close() throws IOException {
		if( mem != null ) {
			mem.disConnect();
		}
	}

	/** A memory file system the server runs as this user in. */
	private FileSource file(final FileSourceUser user) throws IOException {
		mem = new MemoryFileSourceFactory() {
			private static final long serialVersionUID = 1L;

			@Override
			public FileSourceUser whoAmI() {
				return user;
			}
		};
		mem.connect(new Properties());
		FileSource f = mem.createFileSource("/f");
		try (OutputStream out = f.getOutputStream()) {
			out.write(1);
		}
		return f;
	}

	private static Attributes.Settings owner(String name) {
		Attributes.Settings s = new Attributes.Settings();
		s.owner = name;
		return s;
	}

	private static Attributes.Settings group(String name) {
		Attributes.Settings s = new Attributes.Settings();
		s.group = name;
		return s;
	}

	private static Attributes attributes() {
		return new Attributes("example.org", 90, 32768, 32768);
	}

	/** A file that says it can't change an owner or a group the way a provider without that does: with an exception. */
	private static FileSource refusing(final FileSource real, final Exception why) {
		return (FileSource) Proxy.newProxyInstance(FileSource.class.getClassLoader(), new Class<?>[] {FileSource.class}, (proxy, m, args) -> {
			if( m.getName().equals("setOwner") || m.getName().equals("setGroup") ) {
				throw why;
			}
			try {
				return m.invoke(real, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		});
	}

	@Test
	public void aServerThatIsNotTheSuperuserCantGiveAFileAway() throws Exception {
		FileSource f = file(new FileSourceUser(1000, "tester", 1000, "testers"));
		Nfs4Exception e = assertThrows(Nfs4Exception.class, () -> attributes().apply(owner("pat"), f, true));
		assertEquals(Nfs4.NFS4ERR_PERM, e.getStatus());
		assertEquals("tester", f.getOwner().getName(), "the owner stays");
		Nfs4Exception g = assertThrows(Nfs4Exception.class, () -> attributes().apply(group("strangers"), f, true));
		assertEquals(Nfs4.NFS4ERR_PERM, g.getStatus());
		assertEquals("testers", f.getGroup().getName(), "the group stays");
	}

	@Test
	public void aServerThatIsTheSuperuserCan() throws Exception {
		FileSource f = file(new FileSourceUser(0, "root", 0, "wheel"));
		attributes().apply(owner("pat"), f, true);
		attributes().apply(group("staff"), f, true);
		assertEquals("pat", f.getOwner().getName());
		assertEquals("staff", f.getGroup().getName());
	}

	@Test
	public void aProviderThatRefusesWithAnExceptionIsPermToo() throws Exception {
		FileSource real = file(new FileSourceUser(0, "root", 0, "wheel"));
		FileSource f = refusing(real, new UnsupportedOperationException("no owners here"));
		Nfs4Exception e = assertThrows(Nfs4Exception.class, () -> attributes().apply(owner("pat"), f, true));
		assertEquals(Nfs4.NFS4ERR_PERM, e.getStatus(), "not a server fault");
		Nfs4Exception g = assertThrows(Nfs4Exception.class, () -> attributes().apply(group("staff"), f, true));
		assertEquals(Nfs4.NFS4ERR_PERM, g.getStatus());
	}

	/** A real failure is not hidden as a refusal: the caller sees an I/O error (NFS4ERR_IO). */
	@Test
	public void aProviderThatFailsIsAnIoError() throws Exception {
		FileSource real = file(new FileSourceUser(0, "root", 0, "wheel"));
		FileSource f = refusing(real, new IOException("the connection broke"));
		assertThrows(IOException.class, () -> attributes().apply(owner("pat"), f, true));
	}
}
