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

import java.io.IOException;

import us.bringardner.parley.nfs.protocol.Nfs4;

/**
 * A call the server answered with an NFS4ERR status.
 */
public class NfsException extends IOException {

	private static final long serialVersionUID = 1L;
	private final int status;

	public NfsException(int status, String what) {
		super(what+": NFS4 status "+status);
		this.status = status;
	}

	/** @return the NFS4ERR_ status the server answered */
	public int getStatus() {
		return status;
	}

	public boolean isNotFound() {
		return status == Nfs4.NFS4ERR_NOENT;
	}
}
