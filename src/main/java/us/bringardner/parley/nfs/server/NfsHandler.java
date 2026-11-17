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
import java.util.Arrays;

import org.ietf.jgss.GSSException;

import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.AuthSys;
import us.bringardner.parley.nfs.rpc.Rpc;
import us.bringardner.parley.nfs.rpc.RpcCall;
import us.bringardner.parley.nfs.rpc.XdrDecoder;
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
		GssCall gc = null;
		switch (call.getCredentialFlavor()) {
		case Rpc.AUTH_NONE:
			if( !server.isAuthNoneAllowed() ) {
				server.logInfo("Call "+Integer.toUnsignedString(xid)+" refused: AUTH_NONE (no credential) isn't allowed");
				return Rpc.authError(xid, Rpc.AUTH_TOOWEAK);
			}
			caller = Caller.none();
			break;
		case Rpc.AUTH_SYS:
			if( !server.allowsSec(NfsServer.SEC_SYS) ) {
				server.logInfo("Call "+Integer.toUnsignedString(xid)+" refused: AUTH_SYS isn't in "+server.propertyName(NfsServer.PROPERTY_SEC));
				return Rpc.authError(xid, Rpc.AUTH_TOOWEAK);
			}
			try {
				caller = new Caller(Rpc.AUTH_SYS, AuthSys.decode(call.getCredentialBody()));
			} catch (XdrException e) {
				server.logInfo("Call "+Integer.toUnsignedString(xid)+" refused: the AUTH_SYS credential isn't understood ("+e.getMessage()+")");
				return Rpc.authError(xid, Rpc.AUTH_BADCRED);
			}
			break;
		case Rpc.RPCSEC_GSS:
			gc = new GssCall();
			XdrEncoder early = gssAuthenticate(call, whole, gc);
			if( early != null || gc.dropped ) {
				return early;
			}
			if( gc.ctx == null ) {
				// the credential was the whole of the call (DESTROY): it was answered
				return gc.answer;
			}
			caller = Caller.gss(gc.ctx.getPrincipal(), gc.ctx.getUser());
			break;
		default:
			server.logInfo("Call "+Integer.toUnsignedString(xid)+" refused: credential flavor "+call.getCredentialFlavor()+" isn't known");
			return Rpc.authError(xid, Rpc.AUTH_BADCRED);
		}
		if( call.getProgram() != Nfs4.PROGRAM ) {
			return accepted(xid, gc, Rpc.PROG_UNAVAIL, 64);
		}
		if( call.getVersion() != Nfs4.VERSION ) {
			if( gc != null ) {
				XdrEncoder r = accepted(xid, gc, Rpc.PROG_MISMATCH, 64);
				r.writeInt(Nfs4.VERSION);
				r.writeInt(Nfs4.VERSION);
				return r;
			}
			return Rpc.progMismatch(xid, Nfs4.VERSION, Nfs4.VERSION);
		}
		if( gc != null ) {
			return gssProcedure(connection, call, caller, gc);
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


	// ================================================================ RPCSEC_GSS (RFC 2203)

	/** What a RPCSEC_GSS call is: its context and how its arguments and results are protected. */
	private static final class GssCall {
		GssServer.Context ctx;
		long seq;
		int service;
		byte[] verifier = new byte[0];
		XdrDecoder args;
		/** the call is to get no answer */
		boolean dropped;
		/** the answer to a call that needs no procedure (DESTROY) */
		XdrEncoder answer;
	}

	private static byte[] seqBytes(long seq) {
		return new byte[] { (byte) (seq >> 24), (byte) (seq >> 16), (byte) (seq >> 8), (byte) seq };
	}

	/** An accepted reply: a RPCSEC_GSS call's has the checksum of its sequence number as the verifier. */
	private static XdrEncoder accepted(int xid, GssCall gc, int stat, int size) {
		if( gc == null ) {
			return Rpc.startAccepted(xid, stat, size);
		}
		return Rpc.startAccepted(xid, Rpc.RPCSEC_GSS, gc.verifier, stat, size);
	}

	/** A RPCSEC_GSS call refused: said at INFO, because the client only sees a failed mount. */
	private XdrEncoder gssDeny(int xid, int stat) {
		String why = stat == Rpc.AUTH_BADCRED ? "AUTH_BADCRED (credential isn't understood, or the flavor isn't set up)"
				: stat == Rpc.AUTH_BADVERF ? "AUTH_BADVERF (the checksum of the call doesn't check)"
				: stat == Rpc.AUTH_TOOWEAK ? "AUTH_TOOWEAK (that service isn't in parley.nfs.sec)"
				: stat == Rpc.RPCSEC_GSS_CREDPROBLEM ? "RPCSEC_GSS_CREDPROBLEM (no such context)"
				: stat == Rpc.RPCSEC_GSS_CTXPROBLEM ? "RPCSEC_GSS_CTXPROBLEM (the context is used up)"
				: "auth_stat "+stat;
		server.logInfo("RPCSEC_GSS call "+Integer.toUnsignedString(xid)+" refused: "+why);
		return Rpc.authError(xid, stat);
	}

	/**
	 * Check a RPCSEC_GSS call's credential and verifier, and take it through INIT and DESTROY.
	 *
	 * @return a reply to send now (INIT, a refusal), else null: the call carries on with {@code gc}
	 * set, or is dropped when {@code gc.dropped}
	 */
	private XdrEncoder gssAuthenticate(RpcCall call, ByteBuffer whole, GssCall gc) {
		int xid = call.getXid();
		GssServer gs = server.getGss();
		if( gs == null ) {
			return gssDeny(xid, Rpc.AUTH_BADCRED);
		}
		long version;
		int proc;
		int service;
		byte[] handle;
		try {
			XdrDecoder cd = new XdrDecoder(call.getCredentialBody());
			version = cd.readUnsignedInt();
			proc = cd.readInt();
			gc.seq = cd.readUnsignedInt();
			service = cd.readInt();
			handle = cd.readOpaque(Rpc.MAX_AUTH_BYTES);
		} catch (XdrException e) {
			return gssDeny(xid, Rpc.AUTH_BADCRED);
		}
		if( version != 1 ) {
			return gssDeny(xid, Rpc.AUTH_BADCRED);
		}
		if( proc == GssServer.PROC_INIT || proc == GssServer.PROC_CONTINUE_INIT ) {
			return gssInit(call, proc == GssServer.PROC_INIT ? new byte[0] : handle, gs);
		}
		if( proc != GssServer.PROC_DATA && proc != GssServer.PROC_DESTROY ) {
			return gssDeny(xid, Rpc.AUTH_BADCRED);
		}
		GssServer.Context ctx = gs.find(handle);
		if( ctx == null ) {
			return gssDeny(xid, Rpc.RPCSEC_GSS_CREDPROBLEM);
		}
		if( call.getVerifierFlavor() != Rpc.RPCSEC_GSS
				|| !ctx.verifyMic(call.getHeaderThroughCredential(), call.getVerifierBody()) ) {
			return gssDeny(xid, Rpc.AUTH_BADVERF);
		}
		if( gc.seq >= GssServer.MAXSEQ ) {
			gs.destroy(ctx);
			return gssDeny(xid, Rpc.RPCSEC_GSS_CTXPROBLEM);
		}
		if( !ctx.acceptSeq(gc.seq) ) {
			// a repeat of a call, or one too old: it is not answered (RFC 2203 section 5.3.3.1)
			server.logDebug(() -> "RPCSEC_GSS call "+Integer.toUnsignedString(xid)+" from "+ctx.getPrincipal()
				+": sequence number "+gc.seq+" is not new, dropped");
			gc.dropped = true;
			return null;
		}
		try {
			gc.verifier = ctx.mic(seqBytes(gc.seq));
		} catch (GSSException e) {
			server.logDebug(() -> "RPCSEC_GSS checksum failed: "+e.getMessage());
			gc.dropped = true;
			return null;
		}
		if( proc == GssServer.PROC_DESTROY ) {
			gs.destroy(ctx);
			gc.answer = accepted(xid, gc, Rpc.SUCCESS, 64);
			return null;
		}
		if( service != GssServer.SVC_NONE && service != GssServer.SVC_INTEGRITY && service != GssServer.SVC_PRIVACY ) {
			return gssDeny(xid, Rpc.AUTH_BADCRED);
		}
		String flavor = service == GssServer.SVC_PRIVACY ? NfsServer.SEC_KRB5P
				: service == GssServer.SVC_INTEGRITY ? NfsServer.SEC_KRB5I : NfsServer.SEC_KRB5;
		if( !server.allowsSec(flavor) ) {
			return gssDeny(xid, Rpc.AUTH_TOOWEAK);
		}
		server.logDebug(() -> "RPCSEC_GSS call "+Integer.toUnsignedString(xid)+" from "+ctx.getPrincipal()
			+": procedure "+call.getProcedure()+", service "+service+", sequence number "+gc.seq);
		gc.ctx = ctx;
		gc.service = service;
		gc.args = call.getArguments();
		return null;
	}

	/** INIT and CONTINUE_INIT: the client's token goes to the Kerberos exchange, its answer back. */
	private XdrEncoder gssInit(RpcCall call, byte[] handle, GssServer gs) {
		int xid = call.getXid();
		byte[] token;
		try {
			token = call.getArguments().readOpaque(call.getArguments().remaining());
		} catch (XdrException e) {
			return Rpc.startAccepted(xid, Rpc.GARBAGE_ARGS, 64);
		}
		gs.sweep(System.currentTimeMillis());
		GssServer.InitResult r = gs.init(handle, token);
		byte[] verifier = new byte[0];
		int flavor = Rpc.AUTH_NONE;
		if( r.major == GssServer.GSS_S_COMPLETE && r.context != null ) {
			try {
				verifier = r.context.mic(seqBytes(GssServer.SEQ_WINDOW));
				flavor = Rpc.RPCSEC_GSS;
			} catch (GSSException e) {
				return Rpc.startAccepted(xid, Rpc.SYSTEM_ERR, 64);
			}
			server.logInfo("Kerberos context for "+r.context.getPrincipal()+" (user "+r.context.getUser()+")");
		} else if( r.major != GssServer.GSS_S_CONTINUE_NEEDED ) {
			server.logInfo("Kerberos context refused: major "+Integer.toHexString(r.major)+" minor "+r.minor);
		}
		XdrEncoder out = Rpc.startAccepted(xid, flavor, verifier, Rpc.SUCCESS, 256+r.token.length);
		out.writeOpaque(r.handle);
		out.writeUnsignedInt(r.major & 0xFFFFFFFFL);
		out.writeUnsignedInt(r.minor & 0xFFFFFFFFL);
		out.writeInt(GssServer.SEQ_WINDOW);
		out.writeOpaque(r.token);
		return out;
	}

	/** Run a RPCSEC_GSS DATA call: unwrap its arguments, run it, wrap the results as the context's service asks. */
	private XdrEncoder gssProcedure(INioConnection connection, RpcCall call, Caller caller, GssCall gc) {
		int xid = call.getXid();
		byte[] plain;
		try {
			plain = gssArguments(gc);
		} catch (XdrException | GSSException e) {
			server.logDebug(() -> "RPCSEC_GSS arguments of "+Integer.toUnsignedString(xid)+" don't check: "+e.getMessage());
			return accepted(xid, gc, Rpc.GARBAGE_ARGS, 64);
		}
		byte[] results;
		switch (call.getProcedure()) {
		case Nfs4.PROC_NULL:
			results = new byte[0];
			break;
		case Nfs4.PROC_COMPOUND:
			InetAddress from = address(connection.getRemoteAddress());
			ReplayCache cache = server.getReplayCache();
			// a sent-again call has a new sequence number, so the saved results are the plain ones
			ByteBuffer key = null;
			byte[] saved = null;
			if( cache != null ) {
				byte[] h = gc.ctx.getHandle();
				byte[] k = Arrays.copyOf(h, h.length+plain.length);
				System.arraycopy(plain, 0, k, h.length, plain.length);
				key = ByteBuffer.wrap(k);
				saved = cache.find(from, xid, key);
			}
			if( saved != null ) {
				server.logDebug(() -> "Connection "+connection.getId()+": xid "+Integer.toUnsignedString(xid)
					+" sent again, its saved reply is sent");
				results = saved;
			} else {
				XdrEncoder body = new XdrEncoder(1024);
				Compound compound = new Compound(server, caller, from, port(connection.getLocalAddress()));
				try {
					compound.run(new XdrDecoder(plain), body);
				} catch (XdrException e) {
					return accepted(xid, gc, Rpc.GARBAGE_ARGS, 64);
				}
				results = body.toByteArray();
				if( cache != null && compound.isNonIdempotent() ) {
					cache.save(from, xid, key, results);
				}
			}
			break;
		default:
			return accepted(xid, gc, Rpc.PROC_UNAVAIL, 64);
		}
		try {
			byte[] seq = seqBytes(gc.seq);
			XdrEncoder reply;
			switch (gc.service) {
			case GssServer.SVC_INTEGRITY: {
				byte[] data = new byte[4+results.length];
				System.arraycopy(seq, 0, data, 0, 4);
				System.arraycopy(results, 0, data, 4, results.length);
				byte[] mic = gc.ctx.mic(data);
				reply = accepted(xid, gc, Rpc.SUCCESS, data.length+mic.length+64);
				reply.writeOpaque(data);
				reply.writeOpaque(mic);
				break;
			}
			case GssServer.SVC_PRIVACY: {
				byte[] data = new byte[4+results.length];
				System.arraycopy(seq, 0, data, 0, 4);
				System.arraycopy(results, 0, data, 4, results.length);
				byte[] token = gc.ctx.wrap(data);
				reply = accepted(xid, gc, Rpc.SUCCESS, token.length+64);
				reply.writeOpaque(token);
				break;
			}
			default:
				reply = accepted(xid, gc, Rpc.SUCCESS, results.length+64);
				reply.writeRaw(results, 0, results.length);
			}
			return reply;
		} catch (GSSException e) {
			server.logDebug(() -> "RPCSEC_GSS protecting the results of "+Integer.toUnsignedString(xid)+" failed: "+e.getMessage());
			return null;
		}
	}

	/** @return a call's arguments with the context's protection taken off (RFC 2203 section 5.3.2) */
	private byte[] gssArguments(GssCall gc) throws XdrException, GSSException {
		XdrDecoder in = gc.args;
		int max = in.remaining();
		byte[] data;
		switch (gc.service) {
		case GssServer.SVC_INTEGRITY:
			data = in.readOpaque(max);
			byte[] mic = in.readOpaque(max);
			if( !gc.ctx.verifyMic(data, mic) ) {
				throw new GSSException(GSSException.BAD_MIC);
			}
			break;
		case GssServer.SVC_PRIVACY:
			data = gc.ctx.unwrap(in.readOpaque(max));
			break;
		default:
			return rest(in);
		}
		if( data.length < 4 ) {
			throw new XdrException("protected arguments too short");
		}
		long seq = ((data[0] & 0xFFL) << 24) | ((data[1] & 0xFFL) << 16) | ((data[2] & 0xFFL) << 8) | (data[3] & 0xFFL);
		if( seq != gc.seq ) {
			throw new XdrException("protected arguments have sequence number "+seq+", the credential "+gc.seq);
		}
		return Arrays.copyOfRange(data, 4, data.length);
	}

	private static byte[] rest(XdrDecoder in) throws XdrException {
		return in.readFixedOpaque(in.remaining());
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
