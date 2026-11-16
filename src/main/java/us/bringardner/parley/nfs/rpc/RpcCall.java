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

import java.nio.ByteBuffer;

/**
 * The header of an RPC call (RFC 5531 call_body); the procedure's arguments follow in
 * {@link #getArguments()}.
 *
 * @author Tony Bringardner
 */
public final class RpcCall {

	private final int xid;
	private final int rpcVersion;
	private final int program;
	private final int version;
	private final int procedure;
	private final int credFlavor;
	private final byte[] credBody;
	private final int verfFlavor;
	private final byte[] verfBody;
	private final XdrDecoder arguments;
	private final byte[] header;

	private RpcCall(int xid, int rpcVersion, int program, int version, int procedure,
			int credFlavor, byte[] credBody, int verfFlavor, byte[] verfBody, XdrDecoder arguments, byte[] header) {
		this.xid = xid;
		this.rpcVersion = rpcVersion;
		this.program = program;
		this.version = version;
		this.procedure = procedure;
		this.credFlavor = credFlavor;
		this.credBody = credBody;
		this.verfFlavor = verfFlavor;
		this.verfBody = verfBody;
		this.arguments = arguments;
		this.header = header;
	}

	/**
	 * @param record one record
	 * @return the call, or null if the record is a REPLY (a server ignores those)
	 * @throws XdrException if the header doesn't decode; when at least the xid could be read it
	 * is in {@link BadCallException#getXid()}
	 */
	public static RpcCall decode(ByteBuffer record) throws XdrException {
		int start = record.position();
		XdrDecoder in = new XdrDecoder(record);
		int xid = in.readInt();
		try {
			int type = in.readInt();
			if( type == Rpc.REPLY ) {
				return null;
			}
			if( type != Rpc.CALL ) {
				throw new XdrException("Not an RPC call or reply: "+type);
			}
			int rpcvers = in.readInt();
			int prog = in.readInt();
			int vers = in.readInt();
			int proc = in.readInt();
			int cf = in.readInt();
			byte[] cb = in.readOpaque(Rpc.MAX_AUTH_BYTES);
			// what RPCSEC_GSS checksums: the call as far as the end of the credential (RFC 2203 section 5.3.1)
			byte[] head = new byte[in.position()-start];
			record.duplicate().position(start).get(head);
			int vf = in.readInt();
			byte[] vb = in.readOpaque(Rpc.MAX_AUTH_BYTES);
			return new RpcCall(xid, rpcvers, prog, vers, proc, cf, cb, vf, vb, in, head);
		} catch (XdrException e) {
			throw new BadCallException(xid, e.getMessage());
		}
	}

	public int getXid() {
		return xid;
	}

	public int getRpcVersion() {
		return rpcVersion;
	}

	public int getProgram() {
		return program;
	}

	public int getVersion() {
		return version;
	}

	public int getProcedure() {
		return procedure;
	}

	public int getCredentialFlavor() {
		return credFlavor;
	}

	public byte[] getCredentialBody() {
		return credBody.clone();
	}

	public int getVerifierFlavor() {
		return verfFlavor;
	}

	public byte[] getVerifierBody() {
		return verfBody.clone();
	}

	/** @return the bytes of the call from the xid to the end of the credential, which a RPCSEC_GSS verifier checksums */
	public byte[] getHeaderThroughCredential() {
		return header.clone();
	}

	/** @return the procedure's arguments, positioned after the header */
	public XdrDecoder getArguments() {
		return arguments;
	}

	/** A call whose header doesn't decode after the xid; reply GARBAGE_ARGS to it. */
	public static class BadCallException extends XdrException {
		private static final long serialVersionUID = 1L;
		private final int xid;

		public BadCallException(int xid, String message) {
			super(message);
			this.xid = xid;
		}

		public int getXid() {
			return xid;
		}
	}
}
