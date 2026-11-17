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

import java.io.Closeable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import us.bringardner.parley.net.nio.INioConnection;
import us.bringardner.parley.net.nio.INioHandler;
import us.bringardner.parley.net.nio.NioClient;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.RecordMarkingDecoder;
import us.bringardner.parley.nfs.rpc.AuthSys;
import us.bringardner.parley.nfs.rpc.Rpc;
import us.bringardner.parley.nfs.rpc.XdrDecoder;
import us.bringardner.parley.nfs.rpc.XdrEncoder;
import us.bringardner.parley.nfs.server.StateManager.ClientRecord;
import us.bringardner.parley.nfs.server.StateManager.DelegationState;

/**
 * The server's calls to its clients (RFC 7530 section 20): CB_NULL to find out that a client's callback
 * address works, and CB_RECALL to take a delegation back. They go over parley-net's {@link NioClient}, a
 * connection for each call, in a thread of their own, so a client that doesn't answer holds up nobody
 * (decisions D44 to D47 and D56).
 * <p>
 * A callback address is used only when it names the host the client's SETCLIENTID came from (D56): the
 * address is the client's to give, and a server that called any address would be a way to reach hosts the
 * client can't.
 */
public class Delegations implements Closeable {

	/** How long a callback may take, to connect and to answer */
	public static final long CALL_TIMEOUT = 5000;
	private static final int RECORD_LIMIT = 64*1024;

	private final NfsServer server;
	private final NioClient client;
	private final ExecutorService executor;
	private final AtomicInteger xids = new AtomicInteger((int) System.nanoTime());

	public Delegations(NfsServer server) {
		this.server = server;
		client = new NioClient(server.getName()+"-callback");
		client.setConnectTimeout((int) CALL_TIMEOUT);
		executor = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, server.getName()+"-callback");
			t.setDaemon(true);
			return t;
		});
	}

	/** Find out in a thread of its own whether the client's callback address answers; {@code cbUp} says. */
	public void probe(ClientRecord c) {
		c.cbUp = false;
		if( c.cbProgram == 0 ) {
			return;
		}
		executor.execute(() -> {
			try {
				call(c, Nfs4.CB_NULL, new XdrEncoder());
				c.cbUp = true;
				server.logDebug(() -> "callback path of client "+c+" is up");
			} catch (Exception e) {
				c.cbUp = false;
				server.logInfo("Client "+c+" has no working callback address ("+c.cbNetid+" "+c.cbAddr+"): "+e.getMessage()
					+"; it gets no delegations");
			}
		});
	}

	/** Send the CB_RECALL of a delegation, once, in a thread of its own. */
	public void recall(DelegationState d, byte[] fh) {
		if( !d.recallSent.compareAndSet(false, true) ) {
			return;
		}
		executor.execute(() -> {
			ClientRecord c = d.getClient();
			XdrEncoder args = new XdrEncoder();
			args.writeString("").writeInt(0).writeUnsignedInt(c.cbIdent & 0xffffffffL).writeInt(1);
			args.writeInt(Nfs4.OP_CB_RECALL).writeInt(d.stateId().seqid).writeFixedOpaque(d.stateId().other);
			args.writeBoolean(false).writeOpaque(fh);
			try {
				XdrDecoder r = call(c, Nfs4.CB_COMPOUND, args);
				int status = r.readInt();
				if( status != Nfs4.NFS4_OK ) {
					server.logDebug(() -> "CB_RECALL to client "+c+" answered "+status);
				}
			} catch (Exception e) {
				c.cbUp = false;
				server.logInfo("Recalling a delegation from client "+c+" failed: "+e.getMessage()
					+"; it is revoked when the lease runs out");
			}
		});
	}

	/** @return the host and port of a universal address (RFC 7530 section 3.3.9) */
	static InetSocketAddress address(String netid, String addr) {
		if( !"tcp".equals(netid) && !"tcp6".equals(netid) ) {
			throw new IllegalArgumentException("netid "+netid+" isn't tcp or tcp6");
		}
		int p2 = addr.lastIndexOf('.');
		int p1 = p2 < 1 ? -1 : addr.lastIndexOf('.', p2-1);
		if( p1 < 1 ) {
			throw new IllegalArgumentException("address "+addr+" has no port");
		}
		int port = (Integer.parseInt(addr.substring(p1+1, p2)) << 8) | Integer.parseInt(addr.substring(p2+1));
		if( port < 1 || port > 65535 ) {
			throw new IllegalArgumentException("port "+port);
		}
		String host = addr.substring(0, p1);
		try {
			return new InetSocketAddress(InetAddress.getByName(host), port);
		} catch (java.net.UnknownHostException e) {
			throw new IllegalArgumentException("host "+host);
		}
	}

	/** @return the RPC reply's results, after its accept status */
	private XdrDecoder call(ClientRecord c, int proc, XdrEncoder args) throws Exception {
		InetSocketAddress to = address(c.cbNetid, c.cbAddr);
		InetAddress from = c.cbFrom;
		if( from == null || !from.equals(to.getAddress()) ) {
			throw new IllegalArgumentException("it isn't the host "+from+" that the client's SETCLIENTID came from");
		}
		int xid = xids.incrementAndGet();
		XdrEncoder e = new XdrEncoder(128);
		e.writeInt(0); // the record header, set below
		e.writeInt(xid).writeInt(Rpc.CALL).writeInt(Rpc.RPC_VERSION).writeUnsignedInt(c.cbProgram)
			.writeInt(Nfs4.NFS4_CALLBACK_VERSION).writeInt(proc);
		// AUTH_SYS, not AUTH_NONE: a Linux client refuses a callback but CB_NULL that has no credential (the call is denied)
		e.writeInt(Rpc.AUTH_SYS).writeOpaque(new AuthSys((int) System.currentTimeMillis(), "parley-nfs", 0, 0, new long[0]).encode());
		e.writeInt(Rpc.AUTH_NONE).writeOpaque(new byte[0]);
		e.write(args);
		Rpc.finishRecord(e);
		CompletableFuture<byte[]> reply = new CompletableFuture<byte[]>();
		INioHandler handler = (connection, frame) -> {
			byte[] b = new byte[frame.remaining()];
			frame.get(b);
			reply.complete(b);
			connection.close();
		};
		INioConnection connection = client.connectAndWait(to, handler, new RecordMarkingDecoder(RECORD_LIMIT));
		try {
			connection.write(ByteBuffer.wrap(e.array(), 0, e.size()));
			XdrDecoder in = new XdrDecoder(reply.get(CALL_TIMEOUT, TimeUnit.MILLISECONDS));
			if( in.readInt() != xid || in.readInt() != Rpc.REPLY ) {
				throw new IllegalStateException("not the reply to the call");
			}
			if( in.readInt() != Rpc.MSG_ACCEPTED ) {
				throw new IllegalStateException("the call was denied");
			}
			in.readInt();
			in.readOpaque(Rpc.MAX_AUTH_BYTES);
			int accept = in.readInt();
			if( accept != Rpc.SUCCESS ) {
				throw new IllegalStateException("accept status "+accept);
			}
			return in;
		} finally {
			connection.close();
		}
	}

	@Override
	public void close() {
		executor.shutdownNow();
		client.close();
	}
}
