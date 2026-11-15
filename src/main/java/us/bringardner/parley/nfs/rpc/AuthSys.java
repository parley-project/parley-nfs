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
package us.bringardner.parley.nfs.rpc;

import java.util.Arrays;

/**
 * The AUTH_SYS credential (RFC 5531 appendix A, authsys_parms): who the client says the
 * caller is. Nothing proves it; the server decides how far to trust the client machine.
 *
 * @author Tony Bringardner
 */
public final class AuthSys {

	/** RFC 5531: machinename&lt;255&gt; */
	public static final int MAX_MACHINE_NAME = 255;
	/** RFC 5531: gids&lt;16&gt; */
	public static final int MAX_GIDS = 16;

	private final int stamp;
	private final String machineName;
	private final long uid;
	private final long gid;
	private final long[] gids;

	public AuthSys(int stamp, String machineName, long uid, long gid, long[] gids) {
		this.stamp = stamp;
		this.machineName = machineName;
		this.uid = uid;
		this.gid = gid;
		this.gids = gids.clone();
	}

	/**
	 * @param body the credential's body
	 * @throws XdrException if it isn't authsys_parms
	 */
	public static AuthSys decode(byte[] body) throws XdrException {
		XdrDecoder in = new XdrDecoder(body);
		int stamp = in.readInt();
		String machine;
		try {
			machine = in.readString(MAX_MACHINE_NAME);
		} catch (XdrDecoder.InvalidUtf8Exception e) {
			machine = "?";
		}
		long uid = in.readUnsignedInt();
		long gid = in.readUnsignedInt();
		int[] g = in.readIntArray(MAX_GIDS);
		long[] gids = new long[g.length];
		for (int i = 0; i < g.length; i++) {
			gids[i] = g[i] & 0xffffffffL;
		}
		return new AuthSys(stamp, machine, uid, gid, gids);
	}

	public byte[] encode() {
		XdrEncoder out = new XdrEncoder(64);
		out.writeInt(stamp);
		out.writeString(machineName);
		out.writeUnsignedInt(uid);
		out.writeUnsignedInt(gid);
		out.writeInt(gids.length);
		for (long g : gids) {
			out.writeUnsignedInt(g);
		}
		return out.toByteArray();
	}

	public int getStamp() {
		return stamp;
	}

	public String getMachineName() {
		return machineName;
	}

	public long getUid() {
		return uid;
	}

	public long getGid() {
		return gid;
	}

	public long[] getGids() {
		return gids.clone();
	}

	@Override
	public String toString() {
		return "AUTH_SYS uid="+uid+" gid="+gid+" gids="+Arrays.toString(gids)+" machine="+machineName;
	}
}
