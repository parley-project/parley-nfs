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

import us.bringardner.parley.nfs.rpc.AuthSys;
import us.bringardner.parley.nfs.rpc.Rpc;

/**
 * Who sent a call: the RPC credential's flavor and, for AUTH_SYS, the uid and gids.
 *
 * @author Tony Bringardner
 */
public final class Caller {

	private final int flavor;
	private final AuthSys sys;

	public Caller(int flavor, AuthSys sys) {
		this.flavor = flavor;
		this.sys = sys;
	}

	public static Caller none() {
		return new Caller(Rpc.AUTH_NONE, null);
	}

	public int getFlavor() {
		return flavor;
	}

	/** @return the AUTH_SYS credential, null for AUTH_NONE */
	public AuthSys getAuthSys() {
		return sys;
	}

	/** @return the uid, -1 for AUTH_NONE */
	public long getUid() {
		return sys == null ? -1 : sys.getUid();
	}

	/**
	 * @return what identifies the caller for client state (RFC 7530 section 16.33.5: the
	 * principal that made a client ID is the only one that may change it)
	 */
	public String getStateKey() {
		return sys == null ? "none" : "sys:"+sys.getUid();
	}

	@Override
	public String toString() {
		return sys == null ? "AUTH_NONE" : sys.toString();
	}
}
