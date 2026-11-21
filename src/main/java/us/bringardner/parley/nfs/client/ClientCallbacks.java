package us.bringardner.parley.nfs.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.XdrDecoder;
import us.bringardner.parley.nfs.rpc.XdrEncoder;

/**
 * The callback server of a client (RFC 7530 section 16, decision D86): the server connects to it to check that it can
 * call, and to recall a delegation (CB_RECALL). It answers CB_NULL and CB_COMPOUND with CB_RECALL and nothing else.
 */
final class ClientCallbacks implements AutoCloseable {

	/** The program number the client gives the server (any number in the range for programs of an application). */
	static final int PROGRAM = 0x40000000;

	/** A change of a folder the CB_NOTIFY names (D93). */
	static final class Change {
		final int type;
		final String name;
		final String newName;

		Change(int type, String name, String newName) {
			this.type = type;
			this.name = name;
			this.newName = newName;
		}
	}

	/** What the client does when the server tells of changes in a folder it holds a directory delegation of. */
	interface Notifier {
		/** @return the status of CB_NOTIFY */
		int notify(byte[] stateidOther, List<Change> changes);
	}

	/** What the client does when the server recalls a delegation. */
	interface Listener {
		/** @return the NFS4 status to answer: NFS4_OK, or NFS4ERR_BAD_STATEID for a delegation the client does not hold */
		int recall(byte[] stateidOther, byte[] fh);
	}

	private static final int CB_GETATTR = 3;
	private static final System.Logger LOG = System.getLogger(ClientCallbacks.class.getName());

	private final ServerSocket socket;
	private final Thread thread;
	private final Listener listener;
	private final Notifier notifier;
	private final List<Socket> open = Collections.synchronizedList(new ArrayList<Socket>());
	private volatile InetAddress server;
	private volatile boolean closed;

	ClientCallbacks(Listener listener, Notifier notifier) throws IOException {
		this.listener = listener;
		this.notifier = notifier;
		socket = new ServerSocket(0);
		thread = new Thread(this::run, "parley-nfs-client-callbacks");
		thread.setDaemon(true);
		thread.start();
	}

	/** Only the server's own calls are answered. */
	void setServer(InetAddress server) {
		this.server = server;
	}

	int getPort() {
		return socket.getLocalPort();
	}

	/**
	 * @param local the address of this machine the server is connected to
	 * @return the universal address of the callback socket ("h1.h2.h3.h4.p1.p2"), or null for an address that isn't IPv4
	 */
	String universalAddress(InetAddress local) {
		if( !(local instanceof java.net.Inet4Address) ) {
			return null;
		}
		int p = getPort();
		return local.getHostAddress()+"."+(p >> 8)+"."+(p & 255);
	}

	private void run() {
		while( !closed ) {
			try {
				Socket s = socket.accept();
				InetAddress from = s.getInetAddress();
				InetAddress expected = server;
				if( expected != null && !from.equals(expected) && !from.isLoopbackAddress() ) {
					LOG.log(System.Logger.Level.WARNING, "NFS client callback from "+from+" refused: the server is "+expected);
					s.close();
					continue;
				}
				open.add(s);
				Thread t = new Thread(() -> serve(s), "parley-nfs-client-callbacks-conn");
				t.setDaemon(true);
				t.start();
			} catch (IOException e) {
				return;
			}
		}
	}

	private void serve(Socket s) {
		try (Socket c = s) {
			DataInputStream in = new DataInputStream(c.getInputStream());
			OutputStream out = c.getOutputStream();
			while( true ) {
				int header = in.readInt();
				byte[] record = new byte[header & 0x7fffffff];
				in.readFully(record);
				XdrEncoder r = answer(new XdrDecoder(record));
				r.setInt(0, 0x80000000 | (r.size()-4));
				out.write(r.array(), 0, r.size());
				out.flush();
			}
		} catch (IOException | RuntimeException e) {
			// the connection ended, or the call was not one
		} finally {
			open.remove(s);
		}
	}

	/** @return the reply (its record marking header reserved) to a callback CALL message, from the callback socket or a session's back channel */
	XdrEncoder answer(XdrDecoder d) throws IOException {
		int xid = d.readInt();
		d.readInt();
		d.readInt();
		int program = d.readInt();
		int version = d.readInt();
		int proc = d.readInt();
		d.readInt();
		d.readOpaque(400);
		d.readInt();
		d.readOpaque(400);
		// a reply: the xid, REPLY, accepted, no verifier
		XdrEncoder r = new XdrEncoder();
		r.writeInt(0).writeInt(xid).writeInt(1).writeInt(0).writeInt(0).writeOpaque(new byte[0]);
		if( program != PROGRAM ) {
			return r.writeInt(1);
		}
		if( version != Nfs4.NFS4_CALLBACK_VERSION ) {
			return r.writeInt(2).writeInt(Nfs4.NFS4_CALLBACK_VERSION).writeInt(Nfs4.NFS4_CALLBACK_VERSION);
		}
		if( proc == Nfs4.CB_NULL ) {
			return r.writeInt(0);
		}
		if( proc != Nfs4.CB_COMPOUND ) {
			return r.writeInt(3);
		}
		r.writeInt(0);
		d.readString(1024);
		d.readInt();
		d.readInt();
		int count = d.readInt();
		XdrEncoder results = new XdrEncoder();
		int status = Nfs4.NFS4_OK;
		int done = 0;
		for (int i = 0; i < count && status == Nfs4.NFS4_OK; i++) {
			int op = d.readInt();
			if( op == Nfs4.OP_CB_SEQUENCE ) {
				// NFSv4.1 (RFC 8881 section 20.9): the session, the slot and its sequence id, answered as asked
				byte[] session = d.readFixedOpaque(Nfs4.NFS4_SESSIONID_SIZE);
				int seq = d.readInt();
				int slot = d.readInt();
				int highest = d.readInt();
				d.readBoolean();
				int lists = d.readInt();
				if( lists < 0 || lists > 64 ) {
					throw new IOException("referring_call_lists "+lists);
				}
				for (int l = 0; l < lists; l++) {
					d.readFixedOpaque(Nfs4.NFS4_SESSIONID_SIZE);
					int calls = d.readInt();
					if( calls < 0 || calls > 1024 ) {
						throw new IOException("referring_calls "+calls);
					}
					for (int k = 0; k < calls; k++) {
						d.readInt();
						d.readInt();
					}
				}
				results.writeInt(op).writeInt(Nfs4.NFS4_OK).writeFixedOpaque(session).writeInt(seq).writeInt(slot).writeInt(highest).writeInt(highest);
			} else if( op == Nfs4.OP_CB_RECALL ) {
				d.readInt();
				byte[] other = d.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
				d.readBoolean();
				byte[] fh = d.readOpaque(Nfs4.NFS4_FHSIZE);
				status = listener.recall(other, fh);
				results.writeInt(op).writeInt(status);
			} else if( op == Nfs4.OP_CB_NOTIFY ) {
				d.readInt();
				byte[] other = d.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
				d.readOpaque(Nfs4.NFS4_FHSIZE);
				int lists = d.readInt();
				if( lists < 0 || lists > 64 ) {
					throw new IOException("notifications "+lists);
				}
				List<Change> changes = new ArrayList<Change>();
				for (int l = 0; l < lists; l++) {
					int[] mask = d.readIntArray(8);
					parse(mask, new XdrDecoder(d.readOpaque(1 << 20)), changes);
				}
				status = notifier.notify(other, changes);
				results.writeInt(op).writeInt(status);
			} else {
				// the other callbacks (CB_GETATTR is for write delegations, which this client returns at once) are not offered
				boolean getattr = op == CB_GETATTR;
				status = getattr ? Nfs4.NFS4ERR_NOTSUPP : Nfs4.NFS4ERR_OP_ILLEGAL;
				results.writeInt(getattr ? op : Nfs4.OP_ILLEGAL).writeInt(status);
			}
			done++;
		}
		r.writeInt(status).writeString("").writeInt(done).write(results);
		return r;
	}

	/** The values of a notify4 (RFC 8881 section 20.4.1), one per bit set in the mask, in the order of the bits. */
	private static void parse(int[] mask, XdrDecoder v, List<Change> changes) throws IOException {
		int bits = mask.length > 0 ? mask[0] : 0;
		for (int w = 1; w < mask.length; w++) {
			if( mask[w] != 0 ) {
				throw new IOException("a notification type that wasn't asked for");
			}
		}
		for (int bit = 0; bit < 32; bit++) {
			if( (bits & (1 << bit)) == 0 ) {
				continue;
			}
			switch (bit) {
			case Nfs4.NOTIFY4_REMOVE_ENTRY:
				changes.add(new Change(bit, removed(v), null));
				break;
			case Nfs4.NOTIFY4_ADD_ENTRY:
				changes.add(new Change(bit, added(v), null));
				break;
			case Nfs4.NOTIFY4_RENAME_ENTRY:
				String from = removed(v);
				changes.add(new Change(bit, from, added(v)));
				break;
			default:
				throw new IOException("a notification type that wasn't asked for: "+bit);
			}
		}
	}

	/** notify_remove4: the name, its attributes and its cookie */
	private static String removed(XdrDecoder v) throws IOException {
		String name = v.readString(Nfs4.NFS4_OPAQUE_LIMIT);
		v.readIntArray(8);
		v.readOpaque(1 << 16);
		v.readLong();
		return name;
	}

	/** notify_add4: the name, its attributes, the cookie, the previous entry and whether it's the last */
	private static String added(XdrDecoder v) throws IOException {
		String name = v.readString(Nfs4.NFS4_OPAQUE_LIMIT);
		v.readIntArray(8);
		v.readOpaque(1 << 16);
		if( v.readInt() == 1 ) {
			v.readLong();
		}
		if( v.readInt() == 1 ) {
			v.readLong();
			v.readString(Nfs4.NFS4_OPAQUE_LIMIT);
			v.readIntArray(8);
			v.readOpaque(1 << 16);
			v.readBoolean();
			v.readBoolean();
		}
		v.readBoolean();
		return name;
	}

	@Override
	public void close() {
		closed = true;
		try {
			socket.close();
		} catch (IOException e) {
			// it is closed
		}
		synchronized (open) {
			for (Socket s : new ArrayList<Socket>(open)) {
				try {
					s.close();
				} catch (IOException e) {
					// it is closed
				}
			}
		}
		try {
			thread.join(5000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
