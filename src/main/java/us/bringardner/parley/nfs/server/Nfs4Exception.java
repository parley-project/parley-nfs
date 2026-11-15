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

/**
 * An operation ends with an NFSv4 status other than NFS4_OK.
 *
 * @author Tony Bringardner
 */
public class Nfs4Exception extends Exception {

	private static final long serialVersionUID = 1L;
	private final int status;

	public Nfs4Exception(int status) {
		this(status, null);
	}

	public Nfs4Exception(int status, String message) {
		super(message, null, false, false);
		this.status = status;
	}

	/** @return the nfsstat4 */
	public int getStatus() {
		return status;
	}
}
