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
package us.bringardner.parley.nfs;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.AuthSys;
import us.bringardner.parley.nfs.rpc.RecordMarkingDecoder;
import us.bringardner.parley.nfs.rpc.Rpc;
import us.bringardner.parley.nfs.rpc.XdrDecoder;
import us.bringardner.parley.nfs.rpc.XdrEncoder;

/**
 * A minimal NFSv4.0 client for the tests: it sends RPC calls and returns the replies' decoders.
 */
public class TestClient implements Closeable {

	private final Socket socket;
	private final DataInputStream in;
	private final OutputStream out;
	private int xid = 1;
	public int credFlavor = Rpc.AUTH_SYS;
	public long uid = 1000;

	public TestClient(int port) throws IOException {
		this("127.0.0.1", port);
	}

	/** @param host the server's address: the client comes from the same address when it is one of this machine's */
	public TestClient(String host, int port) throws IOException {
		socket = new Socket(host, port);
		socket.setSoTimeout(10000);
		in = new DataInputStream(socket.getInputStream());
		out = socket.getOutputStream();
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}

	/** Send raw bytes (already framed). */
	public void sendRaw(byte[] b) throws IOException {
		out.write(b);
		out.flush();
	}

	/** @return the next record */
	public XdrDecoder readRecord() throws IOException {
		XdrEncoder rec = new XdrEncoder();
		while( true ) {
			int h = in.readInt();
			int len = h & ~RecordMarkingDecoder.LAST_FRAGMENT;
			byte[] b = new byte[len];
			in.readFully(b);
			rec.writeRaw(b, 0, len);
			if( (h & RecordMarkingDecoder.LAST_FRAGMENT) != 0 ) {
				return new XdrDecoder(rec.toByteArray());
			}
		}
	}

	public byte[] callBytes(int xid, int prog, int vers, int proc, XdrEncoder args) {
		XdrEncoder e = new XdrEncoder();
		e.writeInt(xid).writeInt(Rpc.CALL).writeInt(Rpc.RPC_VERSION).writeInt(prog).writeInt(vers).writeInt(proc);
		e.writeInt(credFlavor);
		if( credFlavor == Rpc.AUTH_SYS ) {
			e.writeOpaque(new AuthSys(0, "testhost", uid, 100, new long[] {100}).encode());
		} else {
			e.writeInt(0);
		}
		e.writeInt(Rpc.AUTH_NONE).writeInt(0);
		if( args != null ) {
			e.write(args);
		}
		ByteBuffer f = RecordMarkingDecoder.frame(e);
		byte[] ret = new byte[f.remaining()];
		f.get(ret);
		return ret;
	}

	/**
	 * Call a procedure.
	 * @return the reply, positioned after the accept_stat (checked: SUCCESS), or at the reply_stat if acceptOnly is false
	 */
	public XdrDecoder call(int prog, int vers, int proc, XdrEncoder args) throws IOException {
		int x = xid++;
		sendRaw(callBytes(x, prog, vers, proc, args));
		XdrDecoder r = readRecord();
		if( r.readInt() != x ) {
			throw new IOException("wrong xid");
		}
		if( r.readInt() != Rpc.REPLY ) {
			throw new IOException("not a reply");
		}
		return r;
	}

	/** @return the decoder after the accept_stat SUCCESS of an NFS call */
	public XdrDecoder nfs(int proc, XdrEncoder args) throws IOException {
		XdrDecoder r = call(Nfs4.PROGRAM, Nfs4.VERSION, proc, args);
		int stat = r.readInt();
		if( stat != Rpc.MSG_ACCEPTED ) {
			throw new IOException("denied: "+r.readInt());
		}
		r.readInt();
		r.readOpaque(400);
		int accept = r.readInt();
		if( accept != Rpc.SUCCESS ) {
			throw new IOException("accept_stat "+accept);
		}
		return r;
	}

	/** A COMPOUND being built. */
	public static final class Ops {
		final XdrEncoder ops = new XdrEncoder();
		int count;

		public Ops op(int op) {
			ops.writeInt(op);
			count++;
			return this;
		}

		public Ops putroot() {
			return op(Nfs4.OP_PUTROOTFH);
		}

		public Ops putfh(byte[] fh) {
			op(Nfs4.OP_PUTFH);
			ops.writeOpaque(fh);
			return this;
		}

		public Ops getfh() {
			return op(Nfs4.OP_GETFH);
		}

		public Ops lookup(String name) {
			op(Nfs4.OP_LOOKUP);
			ops.writeString(name);
			return this;
		}

		public Ops getattr(int... bits) {
			op(Nfs4.OP_GETATTR);
			ops.writeIntArray(us.bringardner.parley.nfs.server.Attributes.bitmap(bits));
			return this;
		}

		/** Raw encoder to add an operation's arguments. */
		public XdrEncoder args() {
			return ops;
		}
	}

	/** The reply of a COMPOUND. */
	public static final class Result {
		public final int status;
		public final int count;
		public final XdrDecoder in;

		Result(int status, int count, XdrDecoder in) {
			this.status = status;
			this.count = count;
			this.in = in;
		}

		/**
		 * Read the next result's op and status.
		 * @return the status
		 */
		public int next(int op) throws IOException {
			int got = in.readInt();
			if( got != op ) {
				throw new IOException("expected op "+op+", got "+got);
			}
			return in.readInt();
		}

		/** The next result must be op with NFS4_OK. */
		public Result ok(int op) throws IOException {
			int s = next(op);
			if( s != Nfs4.NFS4_OK ) {
				throw new IOException("op "+Nfs4.opName(op)+" status "+s);
			}
			return this;
		}
	}

	public Result compound(Ops ops) throws IOException {
		return compound(ops, 0);
	}

	/** Send a COMPOUND again with the xid of the last call, as a client does after a reconnect. */
	public Result compoundAgain(Ops ops) throws IOException {
		xid--;
		return compound(ops, 0);
	}

	public Result compound(Ops ops, int minor) throws IOException {
		XdrEncoder a = new XdrEncoder();
		a.writeString("test");
		a.writeInt(minor);
		a.writeInt(ops.count);
		a.write(ops.ops);
		XdrDecoder r = nfs(Nfs4.PROC_COMPOUND, a);
		int status = r.readInt();
		r.readOpaque(1024);
		int count = r.readInt();
		return new Result(status, count, r);
	}
}
