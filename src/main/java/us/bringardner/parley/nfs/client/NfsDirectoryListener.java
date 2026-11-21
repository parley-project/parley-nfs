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

/**
 * What a client is told of a watched folder (D93). Called in a thread of the client that is not the one of any
 * call, so it may use the client, but one that waits long holds up the next change.
 */
public interface NfsDirectoryListener {

	/** An entry was added, removed or renamed in the folder by another client. */
	void changed(NfsDirectoryEvent event);

	/**
	 * The watch has ended: the server took the delegation back (another client needed the folder, or it could not tell
	 * this client of a change), the connection was made again, or the watch was closed. Changes may have been missed
	 * since the last event: look at the folder, and watch it again or poll it.
	 * @param reason in words
	 */
	void lost(String reason);
}
