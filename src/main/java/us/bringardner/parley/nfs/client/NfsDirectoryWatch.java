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

package us.bringardner.parley.nfs.client;

import java.io.Closeable;
import java.io.IOException;

/** A folder the client is told of the changes of, taken with {@link NfsClient#watchDirectory} (D93). */
public final class NfsDirectoryWatch implements Closeable {

	private final NfsClient client;
	private final String path;
	private final NfsClient.DirWatch watch;

	NfsDirectoryWatch(NfsClient client, String path, NfsClient.DirWatch watch) {
		this.client = client;
		this.path = path;
		this.watch = watch;
	}

	public String getPath() {
		return path;
	}

	/** @return true until the watch ends: {@link NfsDirectoryListener#lost} is called then */
	public boolean isActive() {
		return watch.active;
	}

	/** Give the delegation back. {@code lost} is called, if the watch was still active. */
	@Override
	public void close() throws IOException {
		client.closeWatch(watch);
	}
}
