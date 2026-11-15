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

/**
 * ONC RPC version 2 (RFC 5531): message types, reply states and authentication flavors.
 *
 * @author Tony Bringardner
 */
public final class Rpc {

	public static final int RPC_VERSION = 2;

	// msg_type
	public static final int CALL = 0;
	public static final int REPLY = 1;

	// reply_stat
	public static final int MSG_ACCEPTED = 0;
	public static final int MSG_DENIED = 1;

	// accept_stat
	public static final int SUCCESS = 0;
	public static final int PROG_UNAVAIL = 1;
	public static final int PROG_MISMATCH = 2;
	public static final int PROC_UNAVAIL = 3;
	public static final int GARBAGE_ARGS = 4;
	public static final int SYSTEM_ERR = 5;

	// reject_stat
	public static final int RPC_MISMATCH = 0;
	public static final int AUTH_ERROR = 1;

	// auth_stat
	public static final int AUTH_OK = 0;
	public static final int AUTH_BADCRED = 1;
	public static final int AUTH_REJECTEDCRED = 2;
	public static final int AUTH_BADVERF = 3;
	public static final int AUTH_REJECTEDVERF = 4;
	public static final int AUTH_TOOWEAK = 5;

	// auth_flavor
	public static final int AUTH_NONE = 0;
	public static final int AUTH_SYS = 1;
	public static final int AUTH_SHORT = 2;
	public static final int AUTH_DH = 3;
	public static final int RPCSEC_GSS = 6;

	/** RFC 5531: the body of an opaque_auth is at most 400 bytes. */
	public static final int MAX_AUTH_BYTES = 400;

	private Rpc() {
	}

	/**
	 * Start a reply in an encoder: room for the record marking header, the xid and
	 * REPLY. Finish it with {@link #finishRecord(XdrEncoder)}.
	 */
	public static XdrEncoder startReply(int xid, int initialSize) {
		XdrEncoder ret = new XdrEncoder(initialSize);
		ret.reserve(4);
		ret.writeInt(xid);
		ret.writeInt(REPLY);
		return ret;
	}

	/** An accepted reply up to the procedure's results, with an AUTH_NONE verifier. */
	public static XdrEncoder startAccepted(int xid, int acceptStat, int initialSize) {
		XdrEncoder ret = startReply(xid, initialSize);
		ret.writeInt(MSG_ACCEPTED);
		ret.writeInt(AUTH_NONE);
		ret.writeInt(0);
		ret.writeInt(acceptStat);
		return ret;
	}

	/** PROG_MISMATCH with the versions supported. */
	public static XdrEncoder progMismatch(int xid, int low, int high) {
		XdrEncoder ret = startAccepted(xid, PROG_MISMATCH, 64);
		ret.writeInt(low);
		ret.writeInt(high);
		return ret;
	}

	/** MSG_DENIED RPC_MISMATCH: only RPC version 2 is spoken. */
	public static XdrEncoder rpcMismatch(int xid) {
		XdrEncoder ret = startReply(xid, 64);
		ret.writeInt(MSG_DENIED);
		ret.writeInt(RPC_MISMATCH);
		ret.writeInt(RPC_VERSION);
		ret.writeInt(RPC_VERSION);
		return ret;
	}

	/** MSG_DENIED AUTH_ERROR with an auth_stat. */
	public static XdrEncoder authError(int xid, int authStat) {
		XdrEncoder ret = startReply(xid, 64);
		ret.writeInt(MSG_DENIED);
		ret.writeInt(AUTH_ERROR);
		ret.writeInt(authStat);
		return ret;
	}

	/**
	 * Fill in the record marking header reserved by {@link #startReply}: the reply is one last fragment.
	 * @return the encoder, whose bytes from 0 to size() are ready to write
	 */
	public static XdrEncoder finishRecord(XdrEncoder reply) {
		reply.setInt(0, RecordMarkingDecoder.LAST_FRAGMENT | (reply.size()-4));
		return reply;
	}
}
