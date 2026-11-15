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
package us.bringardner.parley.nfs.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import us.bringardner.parley.files.FileSource;

/**
 * Which user registry name an AUTH_SYS uid stands for. AUTH_SYS gives numbers, the registry
 * knows names; the mapping is a properties file:
 * <pre>
 * uid.1000=tony@bringardner.us
 * uid.1001=pat@bringardner.us
 * </pre>
 * A uid that isn't listed (root's 0 included, unless it is listed) is the anonymous user.
 *
 * @author Tony Bringardner
 */
public class IdMap {

	public static final String UID_PREFIX = "uid.";

	private final Map<Long, String> users;

	public IdMap(Map<Long, String> users) {
		this.users = Collections.unmodifiableMap(new HashMap<Long, String>(users));
	}

	/**
	 * @throws IOException if the file can't be read or a line isn't uid.&lt;number&gt;=&lt;name&gt;
	 */
	public static IdMap load(FileSource file) throws IOException {
		Properties p = new Properties();
		try (InputStream in = file.getInputStream()) {
			p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		Map<Long, String> users = new HashMap<Long, String>();
		for (String key : p.stringPropertyNames()) {
			String value = p.getProperty(key).trim();
			if( !key.startsWith(UID_PREFIX) || value.isEmpty() ) {
				throw new IOException(file.getAbsolutePath()+": "+key+" isn't uid.<number>=<user name>");
			}
			try {
				long uid = Long.parseLong(key.substring(UID_PREFIX.length()).trim());
				if( uid < 0 || uid > 0xffffffffL ) {
					throw new NumberFormatException();
				}
				users.put(uid, value);
			} catch (NumberFormatException e) {
				throw new IOException(file.getAbsolutePath()+": "+key+" isn't uid.<number>=<user name>");
			}
		}
		return new IdMap(users);
	}

	/** @return the user name for a uid, null if it isn't mapped */
	public String userName(long uid) {
		return users.get(uid);
	}

	public int size() {
		return users.size();
	}
}
