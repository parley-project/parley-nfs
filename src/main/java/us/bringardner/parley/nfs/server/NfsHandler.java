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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.AuthSys;
import us.bringardner.parley.nfs.rpc.Rpc;
import us.bringardner.parley.nfs.rpc.RpcCall;
import us.bringardner.parley.nfs.rpc.XdrEncoder;
import us.bringardner.parley.nfs.rpc.XdrException;
import us.bringardner.parley.net.nio.INioConnection;
import us.bringardner.parley.net.nio.INioHandler;

/**
 * One connection's RPC calls: each record is a call to the NFS program, answered with one
 * record. Calls run on the server's worker threads, one at a time per connection.
 *
 * @author Tony Bringardner
 */
public class NfsHandler implements INioHandler {

	private final NfsServer server;

	public NfsHandler(NfsServer server) {
		this.server = server;
	}

	/** NFS clients keep their connection while idle; only the server's max idle time closes it. */
	@Override
	public void onMessage(INioConnection connection, ByteBuffer frame) throws Exception {
		XdrEncoder reply = handle(connection, frame);
		if( reply != null ) {
			Rpc.finishRecord(reply);
			connection.write(ByteBuffer.wrap(reply.array(), 0, reply.size()));
		}
	}

	/**
	 * @return the reply (record marking header reserved), or null for none (a REPLY message)
	 */
	XdrEncoder handle(INioConnection connection, ByteBuffer frame) {
		ByteBuffer whole = frame.duplicate();
		RpcCall call;
		try {
			call = RpcCall.decode(frame);
		} catch (RpcCall.BadCallException e) {
			return Rpc.startAccepted(e.getXid(), Rpc.GARBAGE_ARGS, 64);
		} catch (XdrException e) {
			// not even an xid: nothing to answer
			server.logDebug(() -> "Connection "+connection.getId()+": undecodable record ("+e.getMessage()+")");
			return null;
		}
		if( call == null ) {
			return null;
		}
		int xid = call.getXid();
		if( call.getRpcVersion() != Rpc.RPC_VERSION ) {
			return Rpc.rpcMismatch(xid);
		}
		Caller caller;
		switch (call.getCredentialFlavor()) {
		case Rpc.AUTH_NONE:
			if( !server.isAuthNoneAllowed() ) {
				return Rpc.authError(xid, Rpc.AUTH_TOOWEAK);
			}
			caller = Caller.none();
			break;
		case Rpc.AUTH_SYS:
			try {
				caller = new Caller(Rpc.AUTH_SYS, AuthSys.decode(call.getCredentialBody()));
			} catch (XdrException e) {
				return Rpc.authError(xid, Rpc.AUTH_BADCRED);
			}
			break;
		default:
			// RPCSEC_GSS is a later phase (decision D2)
			return Rpc.authError(xid, Rpc.AUTH_BADCRED);
		}
		if( call.getProgram() != Nfs4.PROGRAM ) {
			return Rpc.startAccepted(xid, Rpc.PROG_UNAVAIL, 64);
		}
		if( call.getVersion() != Nfs4.VERSION ) {
			return Rpc.progMismatch(xid, Nfs4.VERSION, Nfs4.VERSION);
		}
		switch (call.getProcedure()) {
		case Nfs4.PROC_NULL:
			return Rpc.startAccepted(xid, Rpc.SUCCESS, 64);
		case Nfs4.PROC_COMPOUND:
			InetAddress from = address(connection.getRemoteAddress());
			ReplayCache cache = server.getReplayCache();
			if( cache != null ) {
				byte[] saved = cache.find(from, xid, whole);
				if( saved != null ) {
					server.logDebug(() -> "Connection "+connection.getId()+": xid "+Integer.toUnsignedString(xid)
						+" sent again, its saved reply is sent");
					XdrEncoder ret = new XdrEncoder(saved.length);
					ret.writeRaw(saved, 0, saved.length);
					return ret;
				}
			}
			XdrEncoder reply = Rpc.startAccepted(xid, Rpc.SUCCESS, 1024);
			int start = reply.size();
			Compound compound = new Compound(server, caller, from, port(connection.getLocalAddress()));
			try {
				compound.run(call.getArguments(), reply);
			} catch (XdrException e) {
				reply.truncate(start-4);
				reply.writeInt(Rpc.GARBAGE_ARGS);
			}
			if( cache != null && compound.isNonIdempotent() ) {
				cache.save(from, xid, whole, reply.toByteArray());
			}
			return reply;
		default:
			return Rpc.startAccepted(xid, Rpc.PROC_UNAVAIL, 64);
		}
	}

	private static InetAddress address(SocketAddress a) {
		return a instanceof InetSocketAddress ? ((InetSocketAddress) a).getAddress() : null;
	}

	private static int port(SocketAddress a) {
		return a instanceof InetSocketAddress ? ((InetSocketAddress) a).getPort() : 0;
	}

	@Override
	public void onError(INioConnection connection, Throwable error) {
		server.logDebug(() -> "Connection "+connection.getId()+": "+error);
	}
}
