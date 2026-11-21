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

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import us.bringardner.parley.core.NamedExecutors;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.AuthSys;
import us.bringardner.parley.nfs.rpc.RecordMarkingDecoder;
import us.bringardner.parley.nfs.rpc.Rpc;
import us.bringardner.parley.nfs.rpc.XdrDecoder;
import us.bringardner.parley.nfs.rpc.XdrEncoder;

/**
 * A generic NFSv4.0 client (D77): AUTH_SYS, one connection, calls one at a time. It mounts one
 * path of a server (an export, or a folder in it) and works on paths below it: information about a
 * file, listing a folder, reading, writing, making and removing. It renews its lease while open.
 * <p>
 * Not here yet (D77 leaves them open): Kerberos, delegations and callbacks, locks, recovery after a
 * server restart (a client ID or state the server no longer knows is an exception), rename, links
 * and attribute changes.
 * <p>
 * Every file is opened by a new open-owner, so the open-owner's sequence ids stay simple.
 */
public class NfsClient implements Closeable {

	/** The most bytes of one READ or WRITE call. */
	public static final int CHUNK = 32768;

	/**
	 * The mode a new folder and a new file ask for. A server that is given none may make them with no permissions
	 * at all (the Linux kernel's does), so every create says what it wants; the server's umask or policy can still narrow it.
	 */
	private static final int DIR_MODE = 0755;
	private static final int FILE_MODE = 0644;

	private static final int[] FILE_ATTRS = bitmap(Nfs4.FATTR4_TYPE, Nfs4.FATTR4_SIZE, Nfs4.FATTR4_FILEID,
			Nfs4.FATTR4_MODE, Nfs4.FATTR4_TIME_MODIFY, Nfs4.FATTR4_OWNER, Nfs4.FATTR4_OWNER_GROUP, Nfs4.FATTR4_NUMLINKS,
			Nfs4.FATTR4_TIME_ACCESS, Nfs4.FATTR4_TIME_METADATA, Nfs4.FATTR4_TIME_CREATE);
	private static final byte[] NO_STATE = new byte[12];
	private static final int RETRIES = 10;
	private static final long RETRY_WAIT = 500;

	private static final System.Logger LOG = System.getLogger(NfsClient.class.getName());

	// the connection is replaced when it is lost (reconnect)
	private volatile Socket socket;
	private volatile DataInputStream in;
	private volatile OutputStream out;
	private final String host;
	private final int port;
	private final String mountPath;
	private final int timeoutMillis;
	private boolean reportedLost;
	private final String machineName;
	private final long uid;
	private final long gid;
	private final String ownerPrefix;
	private int xid = (int) (System.nanoTime() & 0x3fffffff);
	private int openCount;
	private long clientId;
	private byte[] rootHandle;
	private long leaseSeconds = 90;
	private volatile boolean closed;
	private ScheduledExecutorService renewer;
	private final NfsKerberos kerberos;
	// delegations (D86): the callback server, what is held, and the one open-owner the file operations share
	private final ClientCallbacks callbacks;
	private final Map<String, Delegation> delegations = new HashMap<String, Delegation>();
	/** the folders watched through directory delegations (D93), by the stateid; guarded by {@link #delegations} */
	private final Map<String, DirWatch> dirWatches = new HashMap<String, DirWatch>();
	private java.util.concurrent.ExecutorService dirExecutor;
	private java.util.concurrent.ExecutorService returner;
	/** delegations the server recalled, not yet given back */
	private final List<Delegation> pendingReturns = new ArrayList<Delegation>();
	private String sharedOwner;
	private int sharedSeq;
	private int ownerCount;
	/** counts the connections: what is opened on one is not valid on the next */
	private int generation;
	/** the largest file whose bytes a read delegation lets the client keep */
	static final int MAX_CACHED = 4 << 20;
	private final List<NfsLock> locks = new ArrayList<NfsLock>();
	private volatile java.util.function.Consumer<NfsLock> lockLostHandler;
	private int lockCount;
	private volatile NfsKerberos.Session session;
	/** Replies waited for on a connection that has a reader thread (a 4.1 client that takes delegations: callbacks come in on it too). */
	private static final class Link {
		final Map<Integer, java.util.concurrent.CompletableFuture<byte[]>> awaiting = new java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.CompletableFuture<byte[]>>();
		volatile IOException failed;
	}

	/** the reader of the current connection, or null when calls read their own replies */
	private volatile Link link;
	/** one writer at a time on the connection: the calls, and the replies to callbacks */
	private final Object writeLock = new Object();
	/**
	 * The highest NFSv4 minor version this client tries: 2 (the default) tries NFSv4.2, then 4.1, and goes on with 4.0 when the
	 * server doesn't have it; 0 uses 4.0 only. The system property {@value #PROPERTY_MINOR_VERSION} sets it.
	 */
	public static final String PROPERTY_MINOR_VERSION = "parley.nfs.client.minorVersion";
	private final int wantedMinor;
	/** the minor version in use, from the last connect */
	private volatile int minorVersion;
	/** NFSv4.1: the session and the sequence id last used in its (only) slot */
	private byte[] sessionId;
	private int slotSeq;

	/**
	 * Connect, set up a client ID and mount a path.
	 * @param host the server
	 * @param port the server's port (Nfs4.DEFAULT_PORT is 2049)
	 * @param mount the path to mount: "/" (the root), "/docs" (an export) or a folder in one
	 * @param uid the AUTH_SYS user id to claim
	 * @param gid the AUTH_SYS group id to claim
	 * @param timeoutMillis how long to wait for an answer
	 */
	public NfsClient(String host, int port, String mount, long uid, long gid, int timeoutMillis) throws IOException {
		this(host, port, mount, uid, gid, timeoutMillis, null, false);
	}

	/**
	 * Connect, and take read delegations (D86): the server gives one when no one else is using the file, and the
	 * client then keeps the bytes it read, till the server recalls the delegation because another client wants the file.
	 * The client runs a callback server for the recalls, which the server must be able to connect to.
	 * @param delegations true to offer the server a callback address
	 */
	public NfsClient(String host, int port, String mount, long uid, long gid, int timeoutMillis, boolean delegations) throws IOException {
		this(host, port, mount, uid, gid, timeoutMillis, null, delegations);
	}

	/**
	 * Connect with Kerberos (decision D84): the server knows the user by the Kerberos name, so no uid or gid is claimed.
	 * @param kerberos who the user is and how the calls are protected; the caller closes it after this client
	 */
	public NfsClient(String host, int port, String mount, int timeoutMillis, NfsKerberos kerberos) throws IOException {
		this(host, port, mount, 0, 0, timeoutMillis, java.util.Objects.requireNonNull(kerberos, "kerberos"), false);
	}

	/** Connect with Kerberos and take read delegations, as {@link #NfsClient(String, int, String, long, long, int, boolean)} says. */
	public NfsClient(String host, int port, String mount, int timeoutMillis, NfsKerberos kerberos, boolean delegations) throws IOException {
		this(host, port, mount, 0, 0, timeoutMillis, java.util.Objects.requireNonNull(kerberos, "kerberos"), delegations);
	}

	private NfsClient(String host, int port, String mount, long uid, long gid, int timeoutMillis, NfsKerberos kerberos, boolean wantDelegations) throws IOException {
		this.kerberos = kerberos;
		this.wantedMinor = Math.max(0, Math.min(2, Integer.getInteger(PROPERTY_MINOR_VERSION, 2)));
		this.callbacks = wantDelegations ? new ClientCallbacks(this::recalled, this::notified) : null;
		this.uid = uid;
		this.gid = gid;
		String name = "parley-nfs-client";
		try {
			name = java.net.InetAddress.getLocalHost().getHostName();
		} catch (IOException | RuntimeException e) {
			// the default will do
		}
		this.machineName = name;
		byte[] r = new byte[6];
		new SecureRandom().nextBytes(r);
		this.ownerPrefix = "parley-"+machineName+"-"+Long.toHexString(ByteBuffer.wrap(new byte[] {0, 0, r[0], r[1], r[2], r[3], r[4], r[5]}).getLong());
		this.host = host;
		this.port = port;
		this.mountPath = mount;
		this.timeoutMillis = timeoutMillis;
		try {
			connect();
			reclaimComplete();
			startRenewing();
		} catch (IOException | RuntimeException e) {
			close();
			throw e;
		}
	}

	/** Connect, get a client id and mount: the whole state this client has, so it is all that recovery needs. */
	private void connect() throws IOException {
		Socket s = new Socket();
		try {
			s.connect(new InetSocketAddress(host, port), timeoutMillis);
			// with a reader thread the replies are waited for with a timeout of their own
			s.setSoTimeout(callbacks != null && wantedMinor >= 1 ? 0 : timeoutMillis);
			socket = s;
			// a new client id has none of the state of the one before
			synchronized (delegations) {
				delegations.clear();
			}
			endWatches("the connection was made again");
			rotateOwner();
			generation++;
			if( callbacks != null ) {
				callbacks.setServer(s.getInetAddress());
			}
			in = new DataInputStream(s.getInputStream());
			out = s.getOutputStream();
			link = callbacks != null && wantedMinor >= 1 ? startReader(in, out) : null;
			if( kerberos != null ) {
				NfsKerberos.Session n = new NfsKerberos.Session(kerberos);
				session = n;
				n.establish(wire);
			}
			setClientId();
			mount(mountPath);
		} catch (IOException | RuntimeException e) {
			try {
				s.close();
			} catch (IOException ignore) {
				// the first failure is the one to report
			}
			throw e;
		}
	}

	/**
	 * A new connection and a new client id, after the connection was lost, the server restarted or the lease ran
	 * out. Nothing is reclaimed: every operation of this client opens what it needs and closes it again, so no
	 * open, lock or delegation is held between operations.
	 */
	private synchronized void reconnect() throws IOException {
		if( closed ) {
			throw new IOException("the client is closed");
		}
		Socket old = socket;
		if( old != null ) {
			try {
				old.close();
			} catch (IOException ignore) {
				// it is being replaced
			}
		}
		connect();
		reportedLost = false;
		LOG.log(System.Logger.Level.INFO, "NFS client reconnected to "+host+":"+port);
		reclaimLocks();
		reclaimComplete();
	}

	/** One operation of this client, which can be run again. */
	@FunctionalInterface
	private interface Operation<T> {
		T run() throws IOException;
	}

	/**
	 * Run an operation; if the server says the client id or the state is gone (it restarted, the lease ran out) or the
	 * connection is lost, reconnect and run it once more. The server's own rejection (stale or expired client id or
	 * state, a stale handle) means the operation was not done, so any operation is repeated. A lost connection leaves
	 * it unknown whether the operation was done: only one that is safe to repeat (safe is true: reads, and a write or
	 * truncate to given bytes or size) is run again; the others (make a folder, remove, rename) throw the connection's
	 * IOException, after the connection is made again for the caller's next call.
	 */
	private <T> T run(boolean safe, Operation<T> op) throws IOException {
		try {
			return op.run();
		} catch (IOException e) {
			boolean rejected = e instanceof NfsException && isStateGone(((NfsException) e).getStatus());
			boolean lost = isConnectionLost(e);
			if( closed || !(rejected || lost) ) {
				throw e;
			}
			try {
				reconnect();
			} catch (IOException again) {
				e.addSuppressed(again);
				throw e;
			}
			if( lost && !safe ) {
				// the connection is back for the caller's next call, but this one may or may not have been done
				throw e;
			}
			return op.run();
		}
	}

	/** The connection failed (reset, closed by the server, no answer in time), as against an operation the server refused. */
	private static boolean isConnectionLost(IOException e) {
		return e instanceof java.net.SocketException || e instanceof java.io.EOFException || e instanceof java.net.SocketTimeoutException;
	}

	private static boolean isStateGone(int status) {
		return status == Nfs4.NFS4ERR_STALE_CLIENTID || status == Nfs4.NFS4ERR_EXPIRED || status == Nfs4.NFS4ERR_STALE_STATEID
				|| status == Nfs4.NFS4ERR_BAD_STATEID || status == Nfs4.NFS4ERR_STALE || status == Nfs4.NFS4ERR_FHEXPIRED
				|| status == Nfs4.NFS4ERR_BADSESSION || status == Nfs4.NFS4ERR_DEADSESSION || status == Nfs4.NFS4ERR_SEQ_MISORDERED;
	}

	/** @return the NFSv4 minor version in use: 0 or 1 (a session) */
	public int getMinorVersion() {
		return minorVersion;
	}

	/** @return the lease time in seconds the server gave */
	public long getLeaseSeconds() {
		return leaseSeconds;
	}

	@Override
	public void close() throws IOException {
		closed = true;
		ScheduledExecutorService r = renewer;
		if( r != null ) {
			r.shutdownNow();
			try {
				r.awaitTermination(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		synchronized (this) {
			returnAllDelegations();
		}
		if( callbacks != null ) {
			callbacks.close();
		}
		java.util.concurrent.ExecutorService rt = returner;
		if( rt != null ) {
			rt.shutdownNow();
		}
		synchronized (delegations) {
			if( dirExecutor != null ) {
				dirExecutor.shutdown();
			}
		}
		destroySession();
		NfsKerberos.Session g = session;
		if( g != null ) {
			synchronized (this) {
				g.destroy(wire);
			}
		}
		Socket s = socket;
		if( s != null ) {
			s.close();
		}
	}

	// ------------------------------------------------------------------ files

	/**
	 * @param path a path below the mount ("" or "/" is the mount itself)
	 * @return what the server says about it
	 * @throws NfsException with isNotFound() when it doesn't exist
	 */
	public synchronized NfsFileInfo info(String path) throws IOException {
		return run(true, () -> infoOnce(path));
	}

	private NfsFileInfo infoOnce(String path) throws IOException {
		Compound c = new Compound();
		lookup(c, path);
		c.op(Nfs4.OP_GETFH);
		c.op(Nfs4.OP_GETATTR).args.writeIntArray(FILE_ATTRS);
		Reply r = call(c, "info "+path);
		skipLookup(r, path);
		r.ok(Nfs4.OP_GETFH);
		byte[] fh = r.in.readOpaque(Nfs4.NFS4_FHSIZE);
		r.ok(Nfs4.OP_GETATTR);
		return attributes(null, fh, r.in);
	}

	/**
	 * The change attribute (RFC 7530 section 5.8.1.4) of a file or folder: a number the server moves whenever the
	 * object changes, for a folder when an entry is added, removed or renamed. One small GETATTR, no listing, so it
	 * is the cheap way to learn that a folder is as it was. Compare two with equals; the number means nothing else.
	 * @param path a file or folder
	 * @return its change attribute
	 * @throws NfsException with isNotFound() when it doesn't exist
	 */
	public synchronized long changeId(String path) throws IOException {
		return run(true, () -> {
			Compound c = new Compound();
			lookup(c, path);
			c.op(Nfs4.OP_GETATTR).args.writeIntArray(bitmap(Nfs4.FATTR4_CHANGE));
			Reply r = call(c, "changeId "+path);
			skipLookup(r, path);
			r.ok(Nfs4.OP_GETATTR);
			int[] map = r.in.readIntArray(8);
			XdrDecoder a = new XdrDecoder(r.in.readOpaque(Nfs4.NFS4_OPAQUE_LIMIT*64));
			if( map.length == 0 || (map[0] & (1 << Nfs4.FATTR4_CHANGE)) == 0 ) {
				throw new NfsException(Nfs4.NFS4ERR_ATTRNOTSUPP, "the server sent no change attribute for "+path);
			}
			return a.readLong();
		});
	}

	/** @return whether the path exists */
	public boolean exists(String path) throws IOException {
		try {
			info(path);
			return true;
		} catch (NfsException e) {
			if( e.isNotFound() ) {
				return false;
			}
			throw e;
		}
	}

	/**
	 * @param path a folder
	 * @return its entries, without handles (info(path) gives one), in the server's order
	 */
	public synchronized List<NfsFileInfo> list(String path) throws IOException {
		return run(true, () -> listOnce(path));
	}

	private List<NfsFileInfo> listOnce(String path) throws IOException {
		byte[] fh = info(path).getHandle();
		List<NfsFileInfo> ret = new ArrayList<NfsFileInfo>();
		long cookie = 0;
		byte[] verifier = new byte[Nfs4.NFS4_VERIFIER_SIZE];
		while( true ) {
			Compound c = new Compound().putfh(fh);
			c.op(Nfs4.OP_READDIR).args.writeLong(cookie).writeFixedOpaque(verifier).writeInt(CHUNK).writeInt(CHUNK*4)
				.writeIntArray(FILE_ATTRS);
			Reply r = call(c, "list "+path);
			r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_READDIR);
			verifier = r.in.readFixedOpaque(Nfs4.NFS4_VERIFIER_SIZE);
			while( r.in.readBoolean() ) {
				cookie = r.in.readLong();
				String name = r.in.readString(1024);
				ret.add(attributes(name, null, r.in));
			}
			if( r.in.readBoolean() ) {
				return ret;
			}
		}
	}

	/** Make a folder (the one that holds it must exist). */
	public synchronized void mkdir(String path) throws IOException {
		run(false, () -> {
			mkdirOnce(path);
			return null;
		});
	}

	private void mkdirOnce(String path) throws IOException {
		Path p = split(path);
		Compound c = new Compound();
		lookupParent(c, p);
		c.op(Nfs4.OP_CREATE).args.writeInt(Nfs4.NF4DIR).writeString(p.name).writeIntArray(bitmap(Nfs4.FATTR4_MODE)).writeInt(4).writeInt(DIR_MODE);
		Reply r = call(c, "mkdir "+path);
		skipLookup(r, p.parents);
		r.ok(Nfs4.OP_CREATE);
	}

	/** Remove a file or an empty folder. */
	public synchronized void remove(String path) throws IOException {
		run(false, () -> {
			removeOnce(path);
			return null;
		});
	}

	private void removeOnce(String path) throws IOException {
		dropCached();
		Path p = split(path);
		Compound c = new Compound();
		lookupParent(c, p);
		c.op(Nfs4.OP_REMOVE).args.writeString(p.name);
		Reply r = call(c, "remove "+path);
		skipLookup(r, p.parents);
		r.ok(Nfs4.OP_REMOVE);
	}

	/**
	 * Rename or move a file or folder. A file already at the new name is replaced (a folder is replaced only
	 * when it is empty); the folder that is to hold the new name must exist.
	 */
	public synchronized void rename(String from, String to) throws IOException {
		run(false, () -> {
			renameOnce(from, to);
			return null;
		});
	}

	private void renameOnce(String from, String to) throws IOException {
		dropCached();
		Path src = split(from);
		Path dst = split(to);
		Compound c = new Compound();
		lookupParent(c, src);
		c.op(Nfs4.OP_SAVEFH);
		lookupParent(c, dst);
		c.op(Nfs4.OP_RENAME).args.writeString(src.name).writeString(dst.name);
		Reply r = call(c, "rename "+from+" to "+to);
		skipLookup(r, src.parents);
		r.ok(Nfs4.OP_SAVEFH);
		skipLookup(r, dst.parents);
		r.ok(Nfs4.OP_RENAME);
	}

	/**
	 * Make a file exactly size bytes: shorter drops the end, longer adds zeros. The file must exist.
	 * It is opened for writing for this, so the server can tell who changes it.
	 */
	public synchronized void truncate(String path, long size) throws IOException {
		run(true, () -> {
			truncateOnce(path, size);
			return null;
		});
	}

	private void truncateOnce(String path, long size) throws IOException {
		dropCached();
		if( size < 0 ) {
			throw new IllegalArgumentException("a size can't be negative: "+size);
		}
		Opened o = open(path, Nfs4.OPEN4_SHARE_ACCESS_WRITE, false);
		try {
			Compound c = new Compound().putfh(o.fh);
			XdrEncoder a = c.op(Nfs4.OP_SETATTR).args;
			a.writeInt(o.seqid).writeFixedOpaque(o.other);
			a.writeIntArray(bitmap(Nfs4.FATTR4_SIZE)).writeInt(8).writeLong(size);
			Reply r = call(c, "truncate "+path);
			r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_SETATTR);
		} finally {
			closeOpen(o);
		}
	}

	// ------------------------------------------------------------------ NFSv4.2 (RFC 7862)

	/** @return true when the server in use is NFSv4.2 (the operations below need it); true after a connect only */
	public boolean supportsV42() {
		return minorVersion >= 2;
	}

	private void need42(String what) throws IOException {
		if( minorVersion < 2 ) {
			throw new NfsException(Nfs4.NFS4ERR_NOTSUPP, what+" needs NFSv4.2");
		}
	}

	/**
	 * Find the next data or hole of a file (SEEK).
	 * @param hole true to look for a hole, false for data
	 * @return the offset of the next data or hole at or after offset; the end of the file is a hole; -1 when there is
	 * no data at or after offset (the offset is at or past the end)
	 * @throws NfsException NFS4ERR_NOTSUPP when the server is not NFSv4.2
	 */
	public synchronized long seek(String path, long offset, boolean hole) throws IOException {
		return run(true, () -> {
			need42("seek");
			Opened o = open(path, Nfs4.OPEN4_SHARE_ACCESS_READ, false);
			try {
				Compound c = new Compound().putfh(o.fh);
				c.op(Nfs4.OP_SEEK).args.writeInt(o.seqid).writeFixedOpaque(o.other).writeLong(offset)
					.writeInt(hole ? Nfs4.NFS4_CONTENT_HOLE : Nfs4.NFS4_CONTENT_DATA);
				try {
					Reply r = call(c, "seek "+path);
					r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_SEEK);
					r.in.readBoolean();
					return r.in.readLong();
				} catch (NfsException e) {
					if( e.getStatus() == Nfs4.NFS4ERR_NXIO ) {
						return -1L;
					}
					throw e;
				}
			} finally {
				closeOpen(o);
			}
		});
	}

	/**
	 * Read a range with READ_PLUS: the server sends data and holes (ranges of zeros), and the holes are not sent
	 * as bytes. The result has the bytes of the range, the holes as zeros, as {@link #read} would, and says how many
	 * of them were holes.
	 * @throws NfsException NFS4ERR_NOTSUPP when the server is not NFSv4.2
	 */
	public synchronized NfsReadPlus readPlus(String path, long offset, int count) throws IOException {
		return run(true, () -> {
			need42("readPlus");
			Opened o = open(path, Nfs4.OPEN4_SHARE_ACCESS_READ, false);
			try {
				Compound c = new Compound().putfh(o.fh);
				c.op(Nfs4.OP_READ_PLUS).args.writeInt(o.seqid).writeFixedOpaque(o.other).writeLong(offset).writeUnsignedInt(count);
				Reply r = call(c, "readPlus "+path);
				r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_READ_PLUS);
				boolean eof = r.in.readBoolean();
				int segments = r.in.readInt();
				java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
				long holes = 0;
				long next = offset;
				for (int i = 0; i < segments; i++) {
					int type = r.in.readInt();
					long at = r.in.readLong();
					if( at != next ) {
						throw new NfsException(Nfs4.NFS4ERR_SERVERFAULT, "READ_PLUS segment at "+at+", expected "+next);
					}
					if( type == Nfs4.NFS4_CONTENT_DATA ) {
						byte[] d = r.in.readOpaque(Integer.MAX_VALUE);
						bytes.write(d);
						next += d.length;
					} else if( type == Nfs4.NFS4_CONTENT_HOLE ) {
						long n = r.in.readLong();
						if( n < 0 || n > Integer.MAX_VALUE-bytes.size() ) {
							throw new NfsException(Nfs4.NFS4ERR_SERVERFAULT, "READ_PLUS hole of "+n+" bytes");
						}
						bytes.write(new byte[(int) n]);
						holes += n;
						next += n;
					} else {
						throw new NfsException(Nfs4.NFS4ERR_SERVERFAULT, "READ_PLUS segment of type "+type);
					}
				}
				return new NfsReadPlus(bytes.toByteArray(), eof, holes);
			} finally {
				closeOpen(o);
			}
		});
	}

	// ------------------------------------------------------------------ extended attributes (RFC 8276, D101)

	/**
	 * Does the file system of the path keep extended attributes (the xattr_support attribute)?
	 * @return false when the server isn't NFSv4.2, doesn't know the attribute or says no
	 */
	public synchronized boolean isXattrSupported(String path) throws IOException {
		if( minorVersion < 2 ) {
			return false;
		}
		return run(true, () -> {
			Compound c = new Compound();
			lookup(c, path);
			c.op(Nfs4.OP_GETATTR).args.writeIntArray(bitmap(Nfs4.FATTR4_XATTR_SUPPORT));
			Reply r = call(c, "isXattrSupported "+path);
			skipLookup(r, path);
			r.ok(Nfs4.OP_GETATTR);
			int[] map = r.in.readIntArray(8);
			XdrDecoder a = new XdrDecoder(r.in.readOpaque(Nfs4.NFS4_OPAQUE_LIMIT*64));
			int word = Nfs4.FATTR4_XATTR_SUPPORT / 32;
			if( map.length <= word || (map[word] & (1 << (Nfs4.FATTR4_XATTR_SUPPORT % 32))) == 0 ) {
				return false;
			}
			return a.readBoolean();
		});
	}

	/**
	 * Read an extended attribute (GETXATTR).
	 * @return the value, or null when the file has no attribute of that name
	 * @throws NfsException NFS4ERR_NOTSUPP when the server isn't NFSv4.2 or the file system keeps none
	 */
	public synchronized byte[] getXattr(String path, String name) throws IOException {
		return run(true, () -> {
			need42("getXattr");
			Compound c = new Compound();
			lookup(c, path);
			c.op(Nfs4.OP_GETXATTR).args.writeString(name);
			try {
				Reply r = call(c, "getXattr "+path);
				skipLookup(r, path);
				r.ok(Nfs4.OP_GETXATTR);
				return r.in.readOpaque(Integer.MAX_VALUE);
			} catch (NfsException e) {
				if( e.getStatus() == Nfs4.NFS4ERR_NOXATTR ) {
					return null;
				}
				throw e;
			}
		});
	}

	/** Create or replace an extended attribute (SETXATTR, either). */
	public void setXattr(String path, String name, byte[] value) throws IOException {
		setXattr(path, name, value, Nfs4.SETXATTR4_EITHER);
	}

	/**
	 * Create or replace an extended attribute (SETXATTR).
	 * @param option {@link Nfs4#SETXATTR4_EITHER}, {@link Nfs4#SETXATTR4_CREATE} (NFS4ERR_EXIST if there is one) or
	 * {@link Nfs4#SETXATTR4_REPLACE} (NFS4ERR_NOXATTR if there isn't)
	 * @throws NfsException NFS4ERR_XATTR2BIG when the value is too big for the server
	 */
	public synchronized void setXattr(String path, String name, byte[] value, int option) throws IOException {
		run(true, () -> {
			need42("setXattr");
			dropCached();
			Compound c = new Compound();
			lookup(c, path);
			c.op(Nfs4.OP_SETXATTR).args.writeInt(option).writeString(name).writeOpaque(value);
			Reply r = call(c, "setXattr "+path);
			skipLookup(r, path);
			r.ok(Nfs4.OP_SETXATTR);
			return null;
		});
	}

	/**
	 * The names of the extended attributes (LISTXATTRS, as many calls as it takes).
	 * @return the names, in the order the server gave them
	 */
	public synchronized List<String> listXattrs(String path) throws IOException {
		return run(true, () -> {
			need42("listXattrs");
			List<String> names = new ArrayList<>();
			long cookie = 0;
			boolean eof = false;
			while( !eof ) {
				Compound c = new Compound();
				lookup(c, path);
				c.op(Nfs4.OP_LISTXATTRS).args.writeLong(cookie).writeUnsignedInt(32768);
				Reply r = call(c, "listXattrs "+path);
				skipLookup(r, path);
				r.ok(Nfs4.OP_LISTXATTRS);
				long next = r.in.readLong();
				int n = r.in.readInt();
				for (int i = 0; i < n; i++) {
					names.add(r.in.readString(Nfs4.NFS4_OPAQUE_LIMIT));
				}
				eof = r.in.readBoolean();
				if( !eof && next == cookie && n == 0 ) {
					throw new NfsException(Nfs4.NFS4ERR_SERVERFAULT, "LISTXATTRS made no progress");
				}
				cookie = next;
			}
			return names;
		});
	}

	/**
	 * Remove an extended attribute (REMOVEXATTR).
	 * @return false when the file had none of that name
	 */
	public synchronized boolean removeXattr(String path, String name) throws IOException {
		return run(true, () -> {
			need42("removeXattr");
			dropCached();
			Compound c = new Compound();
			lookup(c, path);
			c.op(Nfs4.OP_REMOVEXATTR).args.writeString(name);
			try {
				Reply r = call(c, "removeXattr "+path);
				skipLookup(r, path);
				r.ok(Nfs4.OP_REMOVEXATTR);
				return true;
			} catch (NfsException e) {
				if( e.getStatus() == Nfs4.NFS4ERR_NOXATTR ) {
					return false;
				}
				throw e;
			}
		});
	}

	/** Make sure the range of the file is there (ALLOCATE); a range past the end makes the file longer. */
	public synchronized void allocate(String path, long offset, long length) throws IOException {
		rangeOp(Nfs4.OP_ALLOCATE, "allocate", path, offset, length);
	}

	/** Make the range of the file read as zeros without changing its length (DEALLOCATE). */
	public synchronized void deallocate(String path, long offset, long length) throws IOException {
		rangeOp(Nfs4.OP_DEALLOCATE, "deallocate", path, offset, length);
	}

	private void rangeOp(int op, String what, String path, long offset, long length) throws IOException {
		run(true, () -> {
			need42(what);
			dropCached();
			Opened o = open(path, Nfs4.OPEN4_SHARE_ACCESS_WRITE, false);
			try {
				Compound c = new Compound().putfh(o.fh);
				c.op(op).args.writeInt(o.seqid).writeFixedOpaque(o.other).writeLong(offset).writeLong(length);
				Reply r = call(c, what+" "+path);
				r.ok(Nfs4.OP_PUTFH).ok(op);
			} finally {
				closeOpen(o);
			}
			return null;
		});
	}

	/**
	 * Copy bytes between two files on the server without them passing through this client (COPY).
	 * @param count the bytes to copy; 0 copies to the end of the source
	 * @return the bytes the server copied (it may copy fewer than asked: call again for the rest)
	 */
	public synchronized long copy(String from, long fromOffset, String to, long toOffset, long count) throws IOException {
		return run(true, () -> {
			need42("copy");
			dropCached();
			Opened s = open(from, Nfs4.OPEN4_SHARE_ACCESS_READ, false);
			try {
				Opened d = open(to, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, false);
				try {
					Compound c = new Compound().putfh(s.fh);
					c.op(Nfs4.OP_SAVEFH);
					c.putfh(d.fh);
					XdrEncoder a = c.op(Nfs4.OP_COPY).args;
					a.writeInt(s.seqid).writeFixedOpaque(s.other).writeInt(d.seqid).writeFixedOpaque(d.other);
					a.writeLong(fromOffset).writeLong(toOffset).writeLong(count);
					a.writeBoolean(true).writeBoolean(true).writeInt(0);
					Reply r = call(c, "copy "+from+" to "+to);
					r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_SAVEFH).ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_COPY);
					int ids = r.in.readInt();
					for (int i = 0; i < ids; i++) {
						r.in.readInt();
						r.in.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
					}
					return r.in.readLong();
				} finally {
					closeOpen(d);
				}
			} finally {
				closeOpen(s);
			}
		});
	}

	/**
	 * Read up to count bytes of a file.
	 * @return the bytes (fewer, even none, at the end of the file)
	 */
	public synchronized byte[] read(String path, long offset, int count) throws IOException {
		return run(true, () -> readOnce(path, offset, count));
	}

	private byte[] readOnce(String path, long offset, int count) throws IOException {
		Opened o = open(path, Nfs4.OPEN4_SHARE_ACCESS_READ, false);
		try {
			return readOpen(o, offset, count);
		} finally {
			closeOpen(o);
		}
	}

	/** @return the whole file */
	public synchronized byte[] readAll(String path) throws IOException {
		return run(true, () -> readAllOnce(path));
	}

	private byte[] readAllOnce(String path) throws IOException {
		byte[] kept = keptBytes(path);
		if( kept != null ) {
			return kept;
		}
		Opened o = open(path, Nfs4.OPEN4_SHARE_ACCESS_READ, false);
		try {
			java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
			long offset = 0;
			while( true ) {
				byte[] b = readOpen(o, offset, CHUNK);
				if( b.length == 0 ) {
					byte[] bytes = all.toByteArray();
					Delegation d = o.delegation != null && !o.delegation.write ? o.delegation : delegationOf(o.fh);
					if( d != null && !d.write && bytes.length <= MAX_CACHED ) {
						synchronized (delegations) {
							// only while it is still held: a recall that came meanwhile took it out
							if( delegations.containsKey(key(d.other)) ) {
								d.keep(bytes.clone());
							}
						}
					}
					return bytes;
				}
				all.write(b);
				offset += b.length;
			}
		} finally {
			closeOpen(o);
		}
	}

	/**
	 * The bytes of a file kept under a read delegation, when it is still the file the name leads to: nobody else can
	 * change the file while the delegation is held, but a rename or remove of its name need not recall it.
	 */
	private byte[] keptBytes(String path) throws IOException {
		boolean any = false;
		synchronized (delegations) {
			for (Delegation d : delegations.values()) {
				if( d.cached() != null ) {
					any = true;
					break;
				}
			}
		}
		if( !any ) {
			return null;
		}
		Compound c = new Compound();
		lookup(c, path);
		c.op(Nfs4.OP_GETFH);
		Reply r;
		try {
			r = call(c, "lookup "+path);
			skipLookup(r, path);
			r.ok(Nfs4.OP_GETFH);
		} catch (NfsException e) {
			return null;
		}
		byte[] fh = r.in.readOpaque(Nfs4.NFS4_FHSIZE);
		Delegation d = delegationOf(fh);
		byte[] kept = d == null ? null : d.cached();
		return kept == null ? null : kept.clone();
	}

	/**
	 * Write bytes at an offset, making the file when it isn't there. The write is stable on the
	 * server when this returns. What is already in the file beyond the bytes written stays.
	 */
	public synchronized void write(String path, long offset, byte[] data) throws IOException {
		run(true, () -> {
			writeOnce(path, offset, data);
			return null;
		});
	}

	private void writeOnce(String path, long offset, byte[] data) throws IOException {
		dropCached();
		Opened o = open(path, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true);
		try {
			writeOpen(o, path, offset, data, 0, data.length);
		} finally {
			closeOpen(o);
		}
	}

	private void writeOpen(Opened o, String path, long offset, byte[] data, int off, int len) throws IOException {
		int done = 0;
		while( done < len || len == 0 && done == 0 ) {
			int n = Math.min(CHUNK, len-done);
			Compound c = new Compound().putfh(o.fh);
			XdrEncoder a = c.op(Nfs4.OP_WRITE).args;
			a.writeInt(o.seqid).writeFixedOpaque(o.other);
			a.writeLong(offset+done).writeInt(Nfs4.FILE_SYNC4).writeOpaque(data, off+done, n);
			Reply r = call(c, "write "+path);
			r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_WRITE);
			int count = r.in.readInt();
			if( count <= 0 && n > 0 ) {
				throw new IOException("write "+path+": the server wrote nothing");
			}
			done += count;
			if( n == 0 ) {
				break;
			}
		}
	}

	// ------------------------------------------------------------------ more of a file (D87)

	/**
	 * Make a file that isn't there yet, the server deciding when several callers race for the name.
	 * @return true if this call made it, false if there was something at the path already
	 */
	public synchronized boolean createNew(String path) throws IOException {
		return run(false, () -> {
			dropCached();
			Opened o;
			try {
				o = open(path, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true, true, true);
			} catch (NfsException e) {
				if( e.getStatus() == Nfs4.NFS4ERR_EXIST ) {
					return false;
				}
				throw e;
			}
			closeOpen(o);
			return true;
		});
	}

	/**
	 * What the server lets this client's user do to a path.
	 * @param mask ACCESS4_ bits asked about (Nfs4.ACCESS4_READ ...)
	 * @return the bits the server allows (a path that isn't there is NfsException isNotFound)
	 */
	public synchronized int access(String path, int mask) throws IOException {
		return run(true, () -> {
			Compound c = new Compound();
			lookup(c, path);
			c.op(Nfs4.OP_ACCESS).args.writeInt(mask);
			Reply r = call(c, "access "+path);
			skipLookup(r, path);
			r.ok(Nfs4.OP_ACCESS);
			r.in.readInt();
			return r.in.readInt();
		});
	}

	/** @return the text of a symbolic link */
	public synchronized String readLink(String path) throws IOException {
		return run(true, () -> {
			Compound c = new Compound();
			lookup(c, path);
			c.op(Nfs4.OP_READLINK);
			Reply r = call(c, "readlink "+path);
			skipLookup(r, path);
			r.ok(Nfs4.OP_READLINK);
			return r.in.readString(Nfs4.NFS4_OPAQUE_LIMIT*8);
		});
	}

	/** Make a symbolic link at path that holds this text (the folder that holds it must exist). */
	public synchronized void symlink(String path, String text) throws IOException {
		run(false, () -> {
			Path p = split(path);
			Compound c = new Compound();
			lookupParent(c, p);
			c.op(Nfs4.OP_CREATE).args.writeInt(Nfs4.NF4LNK).writeString(text).writeString(p.name).writeInt(0).writeInt(0);
			Reply r = call(c, "symlink "+path);
			skipLookup(r, p.parents);
			r.ok(Nfs4.OP_CREATE);
			return null;
		});
	}

	/** Change attributes of a file or folder (only those set in the argument). */
	public synchronized void setAttributes(String path, NfsSetAttributes attrs) throws IOException {
		if( attrs.isEmpty() ) {
			return;
		}
		run(true, () -> {
			Compound c = new Compound();
			lookup(c, path);
			XdrEncoder a = c.op(Nfs4.OP_SETATTR).args;
			a.writeInt(0).writeFixedOpaque(NO_STATE);
			byte[] values = attrs.values();
			int[] map = attrs.bitmap();
			int n = map[1] != 0 ? 2 : 1;
			a.writeIntArray(java.util.Arrays.copyOf(map, n)).writeOpaque(values);
			Reply r = call(c, "setattr "+path);
			skipLookup(r, path);
			r.ok(Nfs4.OP_SETATTR);
			return null;
		});
	}

	/**
	 * Keep a file open for reads and writes at offsets.
	 * @param write true to open it for writing too (then it must be writable); false for reading
	 * @param create true to make a file that isn't there (with write)
	 */
	public synchronized NfsOpenFile openFile(String path, boolean write, boolean create) throws IOException {
		NfsOpenFile f = new NfsOpenFile(this, path, write);
		run(false, () -> {
			f.state = openState(path, write, create);
			return null;
		});
		return f;
	}

	private OpenState openState(String path, boolean write, boolean create) throws IOException {
		OpenState st = new OpenState();
		st.opened = open(path, write ? Nfs4.OPEN4_SHARE_ACCESS_BOTH : Nfs4.OPEN4_SHARE_ACCESS_READ, create, false);
		st.generation = generation;
		return st;
	}

	/** After the connection was made again the open is gone: open the file again by its path. */
	private OpenState current(NfsOpenFile f) throws IOException {
		if( f.closed ) {
			throw new IOException("the file is closed: "+f.getPath());
		}
		if( f.state == null || f.state.generation != generation ) {
			f.state = openState(f.getPath(), f.isWritable(), false);
		}
		return f.state;
	}

	synchronized byte[] readFrom(NfsOpenFile f, long offset, int count) throws IOException {
		return run(true, () -> {
			OpenState st = current(f);
			java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream(Math.min(count, CHUNK));
			while( all.size() < count ) {
				byte[] b = readOpen(st.opened, offset+all.size(), count-all.size());
				if( b.length == 0 ) {
					break;
				}
				all.write(b);
			}
			return all.toByteArray();
		});
	}

	synchronized void writeTo(NfsOpenFile f, long offset, byte[] data, int off, int len) throws IOException {
		dropCached();
		run(true, () -> {
			writeOpen(current(f).opened, f.getPath(), offset, data, off, len);
			return null;
		});
	}

	synchronized long sizeOf(NfsOpenFile f) throws IOException {
		return run(true, () -> {
			Compound c = new Compound().putfh(current(f).opened.fh);
			c.op(Nfs4.OP_GETATTR).args.writeIntArray(bitmap(Nfs4.FATTR4_SIZE));
			Reply r = call(c, "size "+f.getPath());
			r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_GETATTR);
			return attributes(null, null, r.in).getSize();
		});
	}

	synchronized void truncateOf(NfsOpenFile f, long size) throws IOException {
		dropCached();
		run(true, () -> {
			Opened o = current(f).opened;
			Compound c = new Compound().putfh(o.fh);
			XdrEncoder a = c.op(Nfs4.OP_SETATTR).args;
			a.writeInt(o.seqid).writeFixedOpaque(o.other);
			a.writeIntArray(bitmap(Nfs4.FATTR4_SIZE)).writeInt(8).writeLong(size);
			call(c, "truncate "+f.getPath()).ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_SETATTR);
			return null;
		});
	}

	synchronized void closeFile(NfsOpenFile f) throws IOException {
		if( f.closed ) {
			return;
		}
		f.closed = true;
		OpenState st = f.state;
		f.state = null;
		if( st != null && st.generation == generation && !this.closed ) {
			try {
				closeOpen(st.opened);
			} catch (IOException e) {
				// the connection or the state is gone, so the open is too
				if( !isConnectionLost(e) && !(e instanceof NfsException && isStateGone(((NfsException) e).getStatus())) ) {
					throw e;
				}
			}
		}
	}

	// ------------------------------------------------------------------ locks (D85)

	/** Called when a lock could not be reclaimed after the connection was made again. */
	public void setLockLostHandler(java.util.function.Consumer<NfsLock> handler) {
		this.lockLostHandler = handler;
	}

	/**
	 * Take a byte range lock, which is held until it is unlocked. The server answers at once: a lock another client
	 * holds is not waited for.
	 * @param length the length, or {@link NfsLock#TO_END}
	 * @param write true for a write (exclusive) lock, which needs write access to the file; false for a read (shared) lock
	 * @throws NfsLockDeniedException when another client holds a conflicting lock
	 */
	public synchronized NfsLock lock(String path, long offset, long length, boolean write) throws IOException {
		return run(false, () -> lockOnce(path, offset, length, write));
	}

	private NfsLock lockOnce(String path, long offset, long length, boolean write) throws IOException {
		Opened o = open(path, write ? Nfs4.OPEN4_SHARE_ACCESS_WRITE : Nfs4.OPEN4_SHARE_ACCESS_READ, false, false);
		NfsLock l = new NfsLock(this, path, offset, length, write, o.owner, ownerPrefix+"-lock-"+(lockCount++));
		l.fh = o.fh;
		l.openSeqid = o.seqid;
		l.openOther = o.other;
		l.openOwnerSeq = o.ownerSeq;
		boolean ok = false;
		try {
			sendLock(l, false);
			ok = true;
		} finally {
			if( !ok ) {
				o.ownerSeq = l.openOwnerSeq;
				closeOpen(o);
			}
		}
		locks.add(l);
		return l;
	}

	/** The first LOCK of a lock-owner (with its open-owner's seqid), taking or reclaiming the range. */
	private void sendLock(NfsLock l, boolean reclaim) throws IOException {
		Compound c = new Compound().putfh(l.fh);
		XdrEncoder a = c.op(Nfs4.OP_LOCK).args;
		a.writeInt(l.isWrite() ? Nfs4.WRITE_LT : Nfs4.READ_LT).writeBoolean(reclaim).writeLong(l.getOffset()).writeLong(l.getLength());
		a.writeBoolean(true).writeInt(l.openOwnerSeq++).writeInt(l.openSeqid).writeFixedOpaque(l.openOther);
		a.writeInt(0).writeLong(clientId).writeOpaque(l.lockOwner.getBytes(StandardCharsets.UTF_8));
		Reply r = call(c, "lock "+l.getPath());
		r.ok(Nfs4.OP_PUTFH);
		int got = r.in.readInt();
		int status = r.in.readInt();
		if( got != Nfs4.OP_LOCK ) {
			throw new IOException("expected LOCK, the server answered "+Nfs4.opName(got));
		}
		if( status == Nfs4.NFS4ERR_DENIED ) {
			throw readDenied(r, "lock "+l.getPath());
		}
		if( status != Nfs4.NFS4_OK ) {
			throw new NfsException(status, "lock "+l.getPath());
		}
		r.in.readInt();
		l.lockOther = r.in.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
		l.lockSeqid = 1;
	}

	private static NfsLockDeniedException readDenied(Reply r, String what) throws IOException {
		long offset = r.in.readLong();
		long length = r.in.readLong();
		boolean write = r.in.readInt() == Nfs4.WRITE_LT;
		r.in.readLong();
		r.in.readOpaque(Nfs4.NFS4_OPAQUE_LIMIT);
		return new NfsLockDeniedException(what, offset, length == -1L ? NfsLock.TO_END : length, write);
	}

	/**
	 * Ask if a lock could be taken.
	 * @return null when it could, otherwise the conflicting lock as the server describes it
	 */
	public synchronized NfsLockDeniedException testLock(String path, long offset, long length, boolean write) throws IOException {
		return run(true, () -> testLockOnce(path, offset, length, write));
	}

	private NfsLockDeniedException testLockOnce(String path, long offset, long length, boolean write) throws IOException {
		Compound c = new Compound();
		lookup(c, path);
		XdrEncoder a = c.op(Nfs4.OP_LOCKT).args;
		a.writeInt(write ? Nfs4.WRITE_LT : Nfs4.READ_LT).writeLong(offset).writeLong(length).writeLong(clientId);
		a.writeOpaque((ownerPrefix+"-test").getBytes(StandardCharsets.UTF_8));
		Reply r = call(c, "test lock "+path);
		skipLookup(r, path);
		int got = r.in.readInt();
		int status = r.in.readInt();
		if( got != Nfs4.OP_LOCKT ) {
			throw new IOException("expected LOCKT, the server answered "+Nfs4.opName(got));
		}
		if( status == Nfs4.NFS4_OK ) {
			return null;
		}
		if( status == Nfs4.NFS4ERR_DENIED ) {
			return readDenied(r, "test lock "+path);
		}
		throw new NfsException(status, "test lock "+path);
	}

	synchronized void unlock(NfsLock l) throws IOException {
		if( l.released ) {
			return;
		}
		run(true, () -> {
			unlockOnce(l);
			return null;
		});
	}

	private void unlockOnce(NfsLock l) throws IOException {
		if( l.released ) {
			return;
		}
		if( !l.lost ) {
			Compound c = new Compound().putfh(l.fh);
			XdrEncoder a = c.op(Nfs4.OP_LOCKU).args;
			a.writeInt(l.isWrite() ? Nfs4.WRITE_LT : Nfs4.READ_LT).writeInt(l.lockSeqid).writeInt(1).writeFixedOpaque(l.lockOther);
			a.writeLong(l.getOffset()).writeLong(l.getLength());
			call(c, "unlock "+l.getPath()).ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_LOCKU);
			l.lockSeqid++;
			if( minorVersion == 0 ) {
				Compound rc = new Compound();
				rc.op(Nfs4.OP_RELEASE_LOCKOWNER).args.writeLong(clientId).writeOpaque(l.lockOwner.getBytes(StandardCharsets.UTF_8));
				call(rc, "release lock owner").ok(Nfs4.OP_RELEASE_LOCKOWNER);
			} else {
				// 4.1 has no lock-owners to release: the lock's stateid is freed
				Compound rc = new Compound();
				rc.op(Nfs4.OP_FREE_STATEID).args.writeInt(0).writeFixedOpaque(l.lockOther);
				call(rc, "free lock stateid").ok(Nfs4.OP_FREE_STATEID);
			}
			Opened o = new Opened();
			o.fh = l.fh;
			o.seqid = l.openSeqid;
			o.other = l.openOther;
			o.ownerSeq = l.openOwnerSeq;
			closeOpen(o);
		}
		l.released = true;
		locks.remove(l);
	}

	/**
	 * After the connection was made again (a new client id): take back each lock, by reclaiming its open and then the
	 * lock, which the server allows in its grace period only. A lock the server refuses is lost, and the caller is told.
	 * A connection lost again is not a lost lock: the next reconnect tries again.
	 */
	private void reclaimLocks() throws IOException {
		for (NfsLock l : new ArrayList<NfsLock>(locks)) {
			if( l.lost || l.released ) {
				continue;
			}
			try {
				Compound c = new Compound().putfh(l.fh);
				l.openOwnerSeq = 0;
				XdrEncoder a = c.op(Nfs4.OP_OPEN).args;
				a.writeInt(l.openOwnerSeq++).writeInt(l.isWrite() ? Nfs4.OPEN4_SHARE_ACCESS_WRITE : Nfs4.OPEN4_SHARE_ACCESS_READ)
					.writeInt(Nfs4.OPEN4_SHARE_DENY_NONE).writeLong(clientId).writeOpaque(l.openOwner.getBytes(StandardCharsets.UTF_8));
				a.writeInt(Nfs4.OPEN4_NOCREATE).writeInt(Nfs4.CLAIM_PREVIOUS).writeInt(Nfs4.OPEN_DELEGATE_NONE);
				Reply r = call(c, "reclaim open "+l.getPath());
				r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_OPEN);
				l.openSeqid = r.in.readInt();
				l.openOther = r.in.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
				r.in.readBoolean();
				r.in.readLong();
				r.in.readLong();
				int rflags = r.in.readInt();
				r.in.readIntArray(8);
				// A server may answer a reclaim with a delegation (Linux does, marked to be returned at once when it can't
				// reach the client's callback address). This client keeps none on an open it holds for a lock: give it back,
				// or the server can't let another client open the file and answers RENEW with CB_PATH_DOWN for ever.
				DelegationReply given = readDelegationReply(r.in, "reclaim open "+l.getPath());
				if( (rflags & Nfs4.OPEN4_RESULT_CONFIRM) != 0 ) {
					Compound cc = new Compound().putfh(l.fh);
					cc.op(Nfs4.OP_OPEN_CONFIRM).args.writeInt(l.openSeqid).writeFixedOpaque(l.openOther).writeInt(l.openOwnerSeq++);
					Reply cr = call(cc, "confirm reclaimed open");
					cr.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_OPEN_CONFIRM);
					l.openSeqid = cr.in.readInt();
					l.openOther = cr.in.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
				}
				sendLock(l, true);
				LOG.log(System.Logger.Level.INFO, "NFS client reclaimed "+l);
				if( given.other != null ) {
					returnDelegation(new Delegation(l.fh, given.seqid, given.other, given.type == Nfs4.OPEN_DELEGATE_WRITE, l.getPath()));
				}
			} catch (NfsException e) {
				lose(l, "the server did not give it back: "+e.getMessage());
			} catch (IOException e) {
				if( isConnectionLost(e) ) {
					throw e;
				}
				lose(l, "it could not be reclaimed: "+e);
			}
		}
	}

	private void lose(NfsLock l, String why) {
		l.lost = true;
		l.lostReason = why;
		locks.remove(l);
		LOG.log(System.Logger.Level.WARNING, "NFS client lost "+l+": "+why);
		java.util.function.Consumer<NfsLock> h = lockLostHandler;
		if( h != null ) {
			try {
				h.accept(l);
			} catch (RuntimeException e) {
				LOG.log(System.Logger.Level.WARNING, "the lock lost handler failed: "+e);
			}
		}
	}

	// ------------------------------------------------------------------ opens

	/** An open of a file: its state in the server. */
	static final class OpenState {
		Opened opened;
		int generation;
	}

	private static final class Opened {
		byte[] fh;
		int seqid;
		byte[] other;
		String owner;
		int ownerSeq;
		/** true when the open-owner is the one the file operations share (the seqid goes back to it) */
		boolean shared;
		String path;
		Delegation delegation;
	}

	/** A read delegation this client holds, and the bytes of the file it has kept under it. */
	private static final class Delegation {
		final byte[] fh;
		final int seqid;
		final byte[] other;
		final boolean write;
		final String path;
		byte[] data;

		Delegation(byte[] fh, int seqid, byte[] other, boolean write, String path) {
			this.fh = fh;
			this.seqid = seqid;
			this.other = other;
			this.write = write;
			this.path = path;
		}

		synchronized byte[] cached() {
			return data;
		}

		synchronized void keep(byte[] bytes) {
			data = bytes;
		}
	}

	private static String key(byte[] other) {
		StringBuilder b = new StringBuilder();
		for (byte x : other) {
			b.append(String.format("%02x", x));
		}
		return b.toString();
	}

	/** A new open-owner for the file operations: the old one's seqid is not known (or its client id is gone). */
	private void rotateOwner() {
		sharedOwner = ownerPrefix+"-files-"+(ownerCount++);
		sharedSeq = 0;
	}

	private Opened open(String path, int access, boolean create) throws IOException {
		return open(path, access, create, true);
	}

	private Opened open(String path, int access, boolean create, boolean shared) throws IOException {
		return open(path, access, create, false, shared);
	}

	private Opened open(String path, int access, boolean create, boolean guarded, boolean shared) throws IOException {
		for (int attempt = 0; ; attempt++) {
			try {
				return openOnce(path, access, create, guarded, shared);
			} catch (IOException | RuntimeException e) {
				if( shared ) {
					// the seqid of the failed OPEN may or may not have counted: an owner of its own is certain
					rotateOwner();
				}
				boolean wait = e instanceof NfsException && (((NfsException) e).getStatus() == Nfs4.NFS4ERR_DELAY
						|| ((NfsException) e).getStatus() == Nfs4.NFS4ERR_GRACE);
				if( !wait || attempt >= RETRIES ) {
					throw e;
				}
				// the server waits for something (a delegation to come back, the end of its grace period): the OPEN is
				// made again, with the next seqid, as the delay counted as an answer
				if( drainReturns() == 0 ) {
					try {
						Thread.sleep(RETRY_WAIT);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new IOException("open "+path+": interrupted");
					}
				}
			}
		}
	}

	private Opened openOnce(String path, int access, boolean create, boolean guarded, boolean shared) throws IOException {
		Path p = split(path);
		Opened o = new Opened();
		o.shared = shared;
		o.path = path;
		if( shared ) {
			o.owner = sharedOwner;
			o.ownerSeq = sharedSeq;
		} else {
			o.owner = ownerPrefix+"-"+(openCount++);
		}
		Compound c = new Compound();
		lookupParent(c, p);
		XdrEncoder a = c.op(Nfs4.OP_OPEN).args;
		a.writeInt(o.ownerSeq++).writeInt(access).writeInt(Nfs4.OPEN4_SHARE_DENY_NONE).writeLong(clientId)
			.writeOpaque(o.owner.getBytes(StandardCharsets.UTF_8));
		if( create ) {
			a.writeInt(Nfs4.OPEN4_CREATE).writeInt(guarded ? Nfs4.GUARDED4 : Nfs4.UNCHECKED4).writeIntArray(bitmap(Nfs4.FATTR4_MODE)).writeInt(4).writeInt(FILE_MODE);
		} else {
			a.writeInt(Nfs4.OPEN4_NOCREATE);
		}
		a.writeInt(Nfs4.CLAIM_NULL).writeString(p.name);
		c.op(Nfs4.OP_GETFH);
		Reply r = call(c, "open "+path, false);
		skipLookup(r, p.parents);
		r.ok(Nfs4.OP_OPEN);
		o.seqid = r.in.readInt();
		o.other = r.in.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
		r.in.readBoolean();
		r.in.readLong();
		r.in.readLong();
		int rflags = r.in.readInt();
		r.in.readIntArray(8);
		DelegationReply dr = readDelegationReply(r.in, "open "+path);
		int delegation = dr.type;
		int dseqid = dr.seqid;
		byte[] dother = dr.other;
		r.ok(Nfs4.OP_GETFH);
		o.fh = r.in.readOpaque(Nfs4.NFS4_FHSIZE);
		if( dother != null ) {
			o.delegation = new Delegation(o.fh, dseqid, dother, delegation == Nfs4.OPEN_DELEGATE_WRITE, path);
		}
		if( (rflags & Nfs4.OPEN4_RESULT_CONFIRM) != 0 ) {
			Compound cc = new Compound().putfh(o.fh);
			cc.op(Nfs4.OP_OPEN_CONFIRM).args.writeInt(o.seqid).writeFixedOpaque(o.other).writeInt(o.ownerSeq++);
			Reply cr = call(cc, "confirm "+path);
			cr.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_OPEN_CONFIRM);
			o.seqid = cr.in.readInt();
			o.other = cr.in.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
		}
		if( shared ) {
			sharedSeq = o.ownerSeq;
		}
		if( o.delegation != null ) {
			if( o.delegation.write ) {
				// this client doesn't write behind a delegation: it is given back with the close
			} else {
				synchronized (delegations) {
					delegations.put(key(o.delegation.other), o.delegation);
				}
			}
		}
		return o;
	}

	/** The delegation (if any) in the reply to an OPEN, which follows the attribute bitmap. */
	private static final class DelegationReply {
		int type = Nfs4.OPEN_DELEGATE_NONE;
		int seqid;
		byte[] other;
	}

	private static DelegationReply readDelegationReply(XdrDecoder in, String what) throws IOException {
		DelegationReply d = new DelegationReply();
		d.type = in.readInt();
		if( d.type == Nfs4.OPEN_DELEGATE_READ || d.type == Nfs4.OPEN_DELEGATE_WRITE ) {
			d.seqid = in.readInt();
			d.other = in.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
			in.readBoolean();
			if( d.type == Nfs4.OPEN_DELEGATE_WRITE ) {
				if( in.readInt() == Nfs4.NFS_LIMIT_SIZE ) {
					in.readLong();
				} else {
					in.readInt();
					in.readInt();
				}
			}
			in.readInt();
			in.readInt();
			in.readInt();
			in.readString(Nfs4.NFS4_OPAQUE_LIMIT);
		} else if( d.type != Nfs4.OPEN_DELEGATE_NONE ) {
			throw new IOException(what+": the server gave a delegation of a kind this client doesn't know: "+d.type);
		}
		return d;
	}

	private byte[] readOpen(Opened o, long offset, int count) throws IOException {
		Compound c = new Compound().putfh(o.fh);
		c.op(Nfs4.OP_READ).args.writeInt(o.seqid).writeFixedOpaque(o.other).writeLong(offset).writeInt(Math.min(count, CHUNK));
		Reply r = call(c, "read");
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_READ);
		r.in.readBoolean();
		return r.in.readOpaque(Integer.MAX_VALUE);
	}

	private void closeOpen(Opened o) throws IOException {
		Compound c = new Compound().putfh(o.fh);
		c.op(Nfs4.OP_CLOSE).args.writeInt(o.ownerSeq++).writeInt(o.seqid).writeFixedOpaque(o.other);
		try {
			Reply r = call(c, "close");
			r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_CLOSE);
		} catch (IOException | RuntimeException e) {
			if( o.shared ) {
				rotateOwner();
			}
			throw e;
		}
		if( o.shared ) {
			sharedSeq = o.ownerSeq;
		}
		if( o.delegation != null && o.delegation.write ) {
			returnDelegation(o.delegation);
		}
	}

	// ------------------------------------------------------------------ delegations (D86)

	/** The server recalls a delegation: stop using what it let this client keep, and give it back. */
	private int recalled(byte[] other, byte[] fh) {
		Delegation d;
		DirWatch w = null;
		synchronized (delegations) {
			d = delegations.remove(key(other));
			if( d == null ) {
				w = dirWatches.remove(key(other));
				if( w != null ) {
					d = new Delegation(w.fh, w.seqid, w.other, false, w.path);
				}
			}
		}
		if( d == null ) {
			return Nfs4.NFS4ERR_BAD_STATEID;
		}
		if( w != null ) {
			lose(w, "the server took the folder's delegation back: another client needs it");
		}
		d.keep(null);
		synchronized (delegations) {
			pendingReturns.add(d);
		}
		LOG.log(System.Logger.Level.DEBUG, "NFS server recalls the delegation of "+d.path);
		// DELEGRETURN goes over the client's connection, which an operation may be using: not in the callback's thread
		synchronized (delegations) {
			if( returner == null ) {
				returner = NamedExecutors.singleThread("parley-nfs-client-delegreturn");
			}
		}
		try {
			returner.execute(() -> {
				synchronized (NfsClient.this) {
					if( !closed ) {
						drainReturns();
					}
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			// the client is closing
		}
		return Nfs4.NFS4_OK;
	}

	/**
	 * Give back the delegations the server recalled. Called by whoever has the connection: the returner's thread, and an
	 * operation that is waiting for the server to answer DELAY because the recall is of a delegation of this client's own.
	 * @return how many were given back
	 */
	private int drainReturns() {
		List<Delegation> list;
		synchronized (delegations) {
			if( pendingReturns.isEmpty() ) {
				return 0;
			}
			list = new ArrayList<Delegation>(pendingReturns);
			pendingReturns.clear();
		}
		for (Delegation d : list) {
			returnDelegation(d);
		}
		return list.size();
	}

	/** Give a delegation back; one the server no longer has (it was revoked, the file was removed) is no matter. */
	private void returnDelegation(Delegation d) {
		try {
			Compound c = new Compound().putfh(d.fh);
			c.op(Nfs4.OP_DELEGRETURN).args.writeInt(d.seqid).writeFixedOpaque(d.other);
			Reply r = call(c, "delegreturn");
			r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_DELEGRETURN);
		} catch (IOException | RuntimeException e) {
			LOG.log(System.Logger.Level.DEBUG, "delegation of "+d.path+" not returned: "+e);
		}
	}

	private void returnAllDelegations() {
		List<Delegation> all;
		List<DirWatch> watches;
		synchronized (delegations) {
			all = new ArrayList<Delegation>(delegations.values());
			delegations.clear();
			watches = new ArrayList<DirWatch>(dirWatches.values());
			dirWatches.clear();
		}
		for (DirWatch w : watches) {
			lose(w, "the client was closed");
			if( socket != null && !socket.isClosed() ) {
				returnDelegation(new Delegation(w.fh, w.seqid, w.other, false, w.path));
			}
		}
		for (Delegation d : all) {
			d.keep(null);
			if( socket != null && !socket.isClosed() ) {
				returnDelegation(d);
			}
		}
	}

	/** Something changes: what a delegation let this client keep may no longer be what is in the file. */
	private void dropCached() {
		synchronized (delegations) {
			for (Delegation d : delegations.values()) {
				d.keep(null);
			}
		}
	}

	/** @return the delegation of a file, or null */
	private Delegation delegationOf(byte[] fh) {
		synchronized (delegations) {
			for (Delegation d : delegations.values()) {
				if( java.util.Arrays.equals(d.fh, fh) ) {
					return d;
				}
			}
		}
		return null;
	}

	/** @return how many delegations this client holds */
	public int getDelegationCount() {
		synchronized (delegations) {
			return delegations.size();
		}
	}

	// ------------------------------------------------------------------ directory delegations (D93, RFC 8881 section 10.9)

	/** A folder watched through a directory delegation. */
	static final class DirWatch {
		final byte[] fh;
		final int seqid;
		final byte[] other;
		final String path;
		final NfsDirectoryListener listener;
		volatile boolean active = true;

		DirWatch(byte[] fh, int seqid, byte[] other, String path, NfsDirectoryListener listener) {
			this.fh = fh;
			this.seqid = seqid;
			this.other = other;
			this.path = path;
			this.listener = listener;
		}
	}

	/**
	 * Be told of the changes other clients make to a folder (a directory delegation, NFSv4.1): entries added, removed
	 * and renamed, as they happen, with no polling. The listener is told when the watch ends ({@link NfsDirectoryListener#lost}),
	 * and then the folder should be looked at again. Only a client that takes delegations (see the constructor with
	 * {@code delegations}) on a server of NFSv4.1 or higher can have this.
	 * @return the watch, or null when the server gives no directory delegation (the server hasn't got them, the
	 * client has no back channel, or another client...): poll the folder then
	 */
	public synchronized NfsDirectoryWatch watchDirectory(String path, NfsDirectoryListener listener) throws IOException {
		java.util.Objects.requireNonNull(listener, "listener");
		return run(true, () -> {
			if( callbacks == null || minorVersion < 1 ) {
				return null;
			}
			Compound c = new Compound();
			lookup(c, path);
			c.op(Nfs4.OP_GETFH);
			XdrEncoder a = c.op(Nfs4.OP_GET_DIR_DELEGATION).args;
			a.writeBoolean(false).writeIntArray(new int[] {(1 << Nfs4.NOTIFY4_REMOVE_ENTRY) | (1 << Nfs4.NOTIFY4_ADD_ENTRY)
					| (1 << Nfs4.NOTIFY4_RENAME_ENTRY)});
			a.writeLong(0).writeInt(0).writeLong(0).writeInt(0).writeIntArray(new int[0]).writeIntArray(new int[0]);
			Reply r = call(c, "watch "+path);
			skipLookup(r, path);
			r.ok(Nfs4.OP_GETFH);
			byte[] fh = r.in.readOpaque(Nfs4.NFS4_FHSIZE);
			int got = r.in.readInt();
			int status = r.in.readInt();
			if( got != Nfs4.OP_GET_DIR_DELEGATION ) {
				throw new IOException("expected GET_DIR_DELEGATION, the server answered "+Nfs4.opName(got));
			}
			if( status == Nfs4.NFS4ERR_NOTSUPP || status == Nfs4.NFS4ERR_OP_ILLEGAL ) {
				return null;
			}
			if( status != Nfs4.NFS4_OK ) {
				throw new NfsException(status, "watch "+path);
			}
			if( r.in.readInt() != Nfs4.GDD4_OK ) {
				r.in.readBoolean();
				return null;
			}
			r.in.readFixedOpaque(Nfs4.NFS4_VERIFIER_SIZE);
			int seqid = r.in.readInt();
			byte[] other = r.in.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
			int[] types = r.in.readIntArray(8);
			int wanted = (1 << Nfs4.NOTIFY4_REMOVE_ENTRY) | (1 << Nfs4.NOTIFY4_ADD_ENTRY) | (1 << Nfs4.NOTIFY4_RENAME_ENTRY);
			if( types.length == 0 || (types[0] & wanted) != wanted ) {
				// a server that keeps the folder's delegation but doesn't tell of changes (Linux nfsd recalls it instead) is
				// no use for a watch: give it back, and the caller polls
				LOG.log(System.Logger.Level.INFO, "NFS server "+host+":"+port+" gave a directory delegation of "+path
						+" that tells of no changes (notification types "+(types.length == 0 ? 0 : types[0])+"): it is given back");
				returnDelegation(new Delegation(fh, seqid, other, false, path));
				return null;
			}
			DirWatch w = new DirWatch(fh, seqid, other, path, listener);
			synchronized (delegations) {
				dirWatches.put(key(other), w);
			}
			return new NfsDirectoryWatch(this, path, w);
		});
	}

	/** CB_NOTIFY: tell the listener of the watched folder, in a thread of its own so it may use this client. */
	private int notified(byte[] other, List<ClientCallbacks.Change> changes) {
		DirWatch w;
		synchronized (delegations) {
			w = dirWatches.get(key(other));
		}
		if( w == null ) {
			return Nfs4.NFS4ERR_BAD_STATEID;
		}
		for (ClientCallbacks.Change ch : changes) {
			NfsDirectoryEvent.Type type = ch.type == Nfs4.NOTIFY4_ADD_ENTRY ? NfsDirectoryEvent.Type.ADDED
					: ch.type == Nfs4.NOTIFY4_REMOVE_ENTRY ? NfsDirectoryEvent.Type.REMOVED : NfsDirectoryEvent.Type.RENAMED;
			NfsDirectoryEvent e = new NfsDirectoryEvent(type, w.path, ch.name, ch.newName);
			deliver(() -> {
				try {
					w.listener.changed(e);
				} catch (RuntimeException ex) {
					LOG.log(System.Logger.Level.WARNING, "A directory listener of "+w.path+" failed: "+ex);
				}
			});
		}
		return Nfs4.NFS4_OK;
	}

	private void deliver(Runnable task) {
		synchronized (delegations) {
			if( dirExecutor == null ) {
				dirExecutor = NamedExecutors.singleThread("parley-nfs-client-watch");
			}
			try {
				dirExecutor.execute(task);
			} catch (java.util.concurrent.RejectedExecutionException e) {
				// the client is closing
			}
		}
	}

	/** The watch is over: tell its listener once. */
	private void lose(DirWatch w, String reason) {
		if( w.active ) {
			w.active = false;
			deliver(() -> {
				try {
					w.listener.lost(reason);
				} catch (RuntimeException ex) {
					LOG.log(System.Logger.Level.WARNING, "A directory listener of "+w.path+" failed: "+ex);
				}
			});
		}
	}

	private void endWatches(String reason) {
		List<DirWatch> all;
		synchronized (delegations) {
			all = new ArrayList<DirWatch>(dirWatches.values());
			dirWatches.clear();
		}
		for (DirWatch w : all) {
			lose(w, reason);
		}
	}

	void closeWatch(DirWatch w) throws IOException {
		boolean mine;
		synchronized (delegations) {
			mine = dirWatches.remove(key(w.other)) != null;
		}
		if( !mine ) {
			return;
		}
		lose(w, "the watch was closed");
		synchronized (this) {
			if( !closed && socket != null && !socket.isClosed() ) {
				returnDelegation(new Delegation(w.fh, w.seqid, w.other, false, w.path));
			}
		}
	}

	/** @return how many folders this client watches */
	public int getWatchCount() {
		synchronized (delegations) {
			return dirWatches.size();
		}
	}

	// ------------------------------------------------------------------ paths

	private static final class Path {
		final List<String> parents;
		final String name;

		Path(List<String> parents, String name) {
			this.parents = parents;
			this.name = name;
		}
	}

	private static List<String> names(String path) {
		List<String> ret = new ArrayList<String>();
		if( path != null ) {
			for (String n : path.split("/")) {
				if( n.isEmpty() ) {
					continue;
				}
				if( n.equals(".") || n.equals("..") ) {
					throw new IllegalArgumentException("a path may not have "+n+" in it: "+path);
				}
				ret.add(n);
			}
		}
		return ret;
	}

	private static Path split(String path) {
		List<String> n = names(path);
		if( n.isEmpty() ) {
			throw new IllegalArgumentException("the mount itself has no name here: "+path);
		}
		return new Path(n.subList(0, n.size()-1), n.get(n.size()-1));
	}

	private void lookup(Compound c, String path) {
		c.putfh(rootHandle);
		for (String n : names(path)) {
			c.op(Nfs4.OP_LOOKUP).args.writeString(n);
		}
	}

	private void lookupParent(Compound c, Path p) {
		c.putfh(rootHandle);
		for (String n : p.parents) {
			c.op(Nfs4.OP_LOOKUP).args.writeString(n);
		}
	}

	private static void skipLookup(Reply r, String path) throws IOException {
		r.ok(Nfs4.OP_PUTFH);
		for (int i = names(path).size(); i > 0; i--) {
			r.ok(Nfs4.OP_LOOKUP);
		}
	}

	private static void skipLookup(Reply r, List<String> parents) throws IOException {
		r.ok(Nfs4.OP_PUTFH);
		for (int i = parents.size(); i > 0; i--) {
			r.ok(Nfs4.OP_LOOKUP);
		}
	}

	// ------------------------------------------------------------------ attributes

	/** Read a fattr4: the bitmap and the values of the attributes this client asks for. */
	private static NfsFileInfo attributes(String name, byte[] fh, XdrDecoder in) throws IOException {
		int[] map = in.readIntArray(8);
		XdrDecoder a = new XdrDecoder(in.readOpaque(Nfs4.NFS4_OPAQUE_LIMIT*64));
		int type = -1;
		long size = -1;
		long fileId = -1;
		int mode = -1;
		long time = -1;
		String owner = null;
		String group = null;
		long atime = -1;
		long ctime = -1;
		long btime = -1;
		int links = -1;
		for (int w = 0; w < map.length; w++) {
			for (int b = 0; b < 32; b++) {
				if( (map[w] & (1 << b)) == 0 ) {
					continue;
				}
				switch (w*32+b) {
				case Nfs4.FATTR4_TYPE: type = a.readInt(); break;
				case Nfs4.FATTR4_SIZE: size = a.readLong(); break;
				case Nfs4.FATTR4_FILEID: fileId = a.readLong(); break;
				case Nfs4.FATTR4_MODE: mode = a.readInt(); break;
				case Nfs4.FATTR4_TIME_MODIFY: time = readTime(a); break;
				case Nfs4.FATTR4_TIME_ACCESS: atime = readTime(a); break;
				case Nfs4.FATTR4_TIME_METADATA: ctime = readTime(a); break;
				case Nfs4.FATTR4_TIME_CREATE: btime = readTime(a); break;
				case Nfs4.FATTR4_OWNER: owner = a.readString(Nfs4.NFS4_OPAQUE_LIMIT); break;
				case Nfs4.FATTR4_OWNER_GROUP: group = a.readString(Nfs4.NFS4_OPAQUE_LIMIT); break;
				case Nfs4.FATTR4_NUMLINKS: links = (int) a.readUnsignedInt(); break;
				default:
					throw new IOException("the server sent attribute "+(w*32+b)+" that was not asked for");
				}
			}
		}
		return new NfsFileInfo(name, fh, type, size, fileId, mode, time, owner, group, atime, ctime, btime, links);
	}

	/** nfstime4: seconds and nanoseconds, as milliseconds */
	private static long readTime(XdrDecoder a) throws IOException {
		long sec = a.readLong();
		long nsec = a.readUnsignedInt();
		return sec*1000+nsec/1000000;
	}

	private static int[] bitmap(int... bits) {
		int max = 0;
		for (int b : bits) {
			max = Math.max(max, b);
		}
		int[] ret = new int[max/32+1];
		for (int b : bits) {
			ret[b/32] |= 1 << (b%32);
		}
		return ret;
	}

	// ------------------------------------------------------------------ set-up and lease

	private void setClientId() throws IOException {
		sessionId = null;
		slotSeq = 0;
		for (int minor = wantedMinor; minor >= 1; minor--) {
			minorVersion = minor;
			try {
				setClientIdV41();
				return;
			} catch (NfsException e) {
				if( e.getStatus() != Nfs4.NFS4ERR_MINOR_VERS_MISMATCH ) {
					throw e;
				}
				LOG.log(System.Logger.Level.INFO, "NFS server "+host+":"+port+" has no NFSv4."+minor+": trying a lower minor version");
			}
		}
		minorVersion = 0;
		byte[] verifier = new byte[Nfs4.NFS4_VERIFIER_SIZE];
		new SecureRandom().nextBytes(verifier);
		Compound c = new Compound();
		XdrEncoder a = c.op(Nfs4.OP_SETCLIENTID).args;
		a.writeFixedOpaque(verifier).writeOpaque(ownerPrefix.getBytes(StandardCharsets.UTF_8));
		String cb = callbacks == null ? null : callbacks.universalAddress(socket.getLocalAddress());
		if( cb == null ) {
			// no callback: the server can't call this client, so it gives no delegations
			a.writeInt(0).writeString("tcp").writeString("0.0.0.0.0.0").writeInt(0);
		} else {
			a.writeInt(ClientCallbacks.PROGRAM).writeString("tcp").writeString(cb).writeInt(1);
		}
		Reply r = call(c, "setclientid");
		r.ok(Nfs4.OP_SETCLIENTID);
		clientId = r.in.readLong();
		byte[] confirm = r.in.readFixedOpaque(Nfs4.NFS4_VERIFIER_SIZE);
		Compound cc = new Compound();
		cc.op(Nfs4.OP_SETCLIENTID_CONFIRM).args.writeLong(clientId).writeFixedOpaque(confirm);
		call(cc, "setclientid_confirm").ok(Nfs4.OP_SETCLIENTID_CONFIRM);
	}

	/** EXCHANGE_ID and CREATE_SESSION (RFC 8881 sections 18.35 and 18.36): a client id and a session. */
	private void setClientIdV41() throws IOException {
		byte[] verifier = new byte[Nfs4.NFS4_VERIFIER_SIZE];
		new SecureRandom().nextBytes(verifier);
		Compound c = new Compound().withoutSession();
		XdrEncoder a = c.op(Nfs4.OP_EXCHANGE_ID).args;
		a.writeFixedOpaque(verifier).writeOpaque(ownerPrefix.getBytes(StandardCharsets.UTF_8));
		a.writeInt(EXCHGID4_FLAG_USE_NON_PNFS).writeInt(0);
		a.writeInt(1).writeString("bringardner.us").writeString("parley-nfs-client").writeLong(0).writeInt(0);
		Reply r = call(c, "exchange_id");
		r.ok(Nfs4.OP_EXCHANGE_ID);
		clientId = r.in.readLong();
		int sequence = r.in.readInt();

		Compound cs = new Compound().withoutSession();
		a = cs.op(Nfs4.OP_CREATE_SESSION).args;
		// CREATE_SESSION4_FLAG_CONN_BACK_CHAN: the server's callbacks come back over this connection
		a.writeLong(clientId).writeInt(sequence).writeInt(callbacks != null ? 2 : 0);
		// the fore channel: one request at a time (every operation of this client is synchronized), none kept for a retry
		a.writeInt(0).writeInt(SESSION_MAX_SIZE).writeInt(SESSION_MAX_SIZE).writeInt(0).writeInt(32).writeInt(1).writeInt(0);
		// the back channel, which isn't used yet
		a.writeInt(0).writeInt(4096).writeInt(4096).writeInt(0).writeInt(2).writeInt(1).writeInt(0);
		a.writeInt(ClientCallbacks.PROGRAM).writeInt(1);
		if( callbacks != null ) {
			a.writeInt(Rpc.AUTH_SYS).writeInt((int) (System.currentTimeMillis()/1000)).writeString(machineName).writeInt((int) uid).writeInt((int) gid).writeInt(0);
		} else {
			a.writeInt(Rpc.AUTH_NONE);
		}
		r = call(cs, "create_session");
		r.ok(Nfs4.OP_CREATE_SESSION);
		sessionId = r.in.readFixedOpaque(Nfs4.NFS4_SESSIONID_SIZE);
		r.in.readInt();
		int granted = r.in.readInt();
		slotSeq = 0;
		if( callbacks != null && (granted & 2) == 0 ) {
			LOG.log(System.Logger.Level.INFO, "NFS server "+host+":"+port+" gave no back channel: it gives this client no delegations");
		}
	}

	private static final int EXCHGID4_FLAG_USE_NON_PNFS = 0x00010000;
	/** the largest request or reply asked of a session: the biggest READ or WRITE and its header */
	private static final int SESSION_MAX_SIZE = (1 << 20) + 65536;

	/** NFSv4.1: give the session and the client id back, so the server doesn't keep them for a lease; no error matters here. */
	private synchronized void destroySession() {
		byte[] id = sessionId;
		if( minorVersion < 1 || id == null || socket == null || socket.isClosed() ) {
			return;
		}
		try {
			Compound c = new Compound().withoutSession();
			c.op(Nfs4.OP_DESTROY_SESSION).args.writeFixedOpaque(id);
			sessionId = null;
			call(c, "destroy_session", false).ok(Nfs4.OP_DESTROY_SESSION);
			Compound d = new Compound().withoutSession();
			d.op(Nfs4.OP_DESTROY_CLIENTID).args.writeLong(clientId);
			call(d, "destroy_clientid", false).ok(Nfs4.OP_DESTROY_CLIENTID);
		} catch (IOException | RuntimeException e) {
			LOG.log(System.Logger.Level.DEBUG, "NFS client: the session was not given back: "+e);
		}
	}

	/** RECLAIM_COMPLETE (NFSv4.1): this client has taken back what it held before the server restarted, and has no more to take. */
	private void reclaimComplete() throws IOException {
		if( minorVersion == 0 ) {
			return;
		}
		Compound c = new Compound();
		c.op(Nfs4.OP_RECLAIM_COMPLETE).args.writeBoolean(false);
		try {
			call(c, "reclaim_complete").ok(Nfs4.OP_RECLAIM_COMPLETE);
		} catch (NfsException e) {
			if( e.getStatus() != Nfs4.NFS4ERR_COMPLETE_ALREADY ) {
				throw e;
			}
		}
	}

	private void mount(String path) throws IOException {
		Compound c = new Compound().op(Nfs4.OP_PUTROOTFH);
		for (String n : names(path)) {
			c.op(Nfs4.OP_LOOKUP).args.writeString(n);
		}
		c.op(Nfs4.OP_GETFH);
		c.op(Nfs4.OP_GETATTR).args.writeIntArray(bitmap(Nfs4.FATTR4_LEASE_TIME));
		Reply r = call(c, "mount "+path);
		r.ok(Nfs4.OP_PUTROOTFH);
		for (int i = names(path).size(); i > 0; i--) {
			r.ok(Nfs4.OP_LOOKUP);
		}
		r.ok(Nfs4.OP_GETFH);
		rootHandle = r.in.readOpaque(Nfs4.NFS4_FHSIZE);
		r.ok(Nfs4.OP_GETATTR);
		int[] map = r.in.readIntArray(8);
		XdrDecoder a = new XdrDecoder(r.in.readOpaque(Nfs4.NFS4_OPAQUE_LIMIT));
		if( map.length > 0 && (map[0] & (1 << Nfs4.FATTR4_LEASE_TIME)) != 0 ) {
			long lease = a.readUnsignedInt();
			if( lease > 0 ) {
				leaseSeconds = lease;
			}
		}
	}

	private void startRenewing() {
		long period = Math.max(1, leaseSeconds/2);
		renewer = NamedExecutors.singleThreadScheduled("parley-nfs-client-renew");
		renewer.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				renew();
			}
		}, period, period, TimeUnit.SECONDS);
	}

	private synchronized void renew() {
		if( closed ) {
			return;
		}
		try {
			Compound c = new Compound();
			if( minorVersion == 0 ) {
				c.op(Nfs4.OP_RENEW).args.writeLong(clientId);
				call(c, "renew").ok(Nfs4.OP_RENEW);
			} else {
				// any call in a session renews the lease: SEQUENCE alone does
				call(c, "renew");
			}
		} catch (NfsException e) {
			if( e.getStatus() != Nfs4.NFS4ERR_CB_PATH_DOWN ) {
				renewFailed(e);
				return;
			}
			// The lease was renewed (RFC 7530 and the Linux server renew first); the server only says it can't reach this
			// client's callback address, so it could not recall a delegation. Give them back and keep everything else:
			// a new client id would throw away the open files and locks the client holds.
			LOG.log(System.Logger.Level.INFO, "NFS server "+host+":"+port+" can't reach this client's callback address (RENEW: "
					+"CB_PATH_DOWN): the lease is renewed; the delegations are given back");
			returnAllDelegations();
		} catch (IOException | RuntimeException e) {
			renewFailed(e);
		}
	}

	/** The lease or the connection is gone: get them back now, so the next call finds a client that works. */
	private void renewFailed(Exception e) {
		LOG.log(System.Logger.Level.INFO, "NFS client's RENEW failed ("+e+"): making a new client id");
		try {
			reconnect();
		} catch (IOException | RuntimeException again) {
			if( !reportedLost ) {
				reportedLost = true;
				LOG.log(System.Logger.Level.WARNING, "NFS client lost its server "+host+":"+port+" ("+e+"); trying again every "
						+Math.max(1, leaseSeconds/2)+" seconds");
			}
		}
	}

	// ------------------------------------------------------------------ RPC

	private static final class Compound {
		final XdrEncoder ops = new XdrEncoder();
		XdrEncoder args;
		int count;
		/** NFSv4.1: the COMPOUND is not in a session (EXCHANGE_ID, CREATE_SESSION, DESTROY_CLIENTID) */
		boolean sessionless;

		Compound withoutSession() {
			sessionless = true;
			return this;
		}

		Compound op(int op) {
			ops.writeInt(op);
			count++;
			args = ops;
			return this;
		}

		Compound putfh(byte[] fh) {
			op(Nfs4.OP_PUTFH);
			ops.writeOpaque(fh);
			return this;
		}
	}

	private static final class Reply {
		final XdrDecoder in;

		Reply(XdrDecoder in) {
			this.in = in;
		}

		Reply ok(int op) throws IOException {
			int got = in.readInt();
			int status = in.readInt();
			if( got != op ) {
				throw new IOException("expected "+Nfs4.opName(op)+", the server answered "+Nfs4.opName(got));
			}
			if( status != Nfs4.NFS4_OK ) {
				throw new NfsException(status, Nfs4.opName(op));
			}
			return this;
		}
	}

	/** Send a COMPOUND, again a few times while the server says to wait (DELAY, GRACE). */
	private Reply call(Compound c, String what) throws IOException {
		return call(c, what, true);
	}

	/** @param again false when DELAY and GRACE are not to be answered by sending the same bytes again (an OPEN: its seqid is used up) */
	private Reply call(Compound c, String what, boolean again) throws IOException {
		for (int attempt = 0; ; attempt++) {
			XdrEncoder a = new XdrEncoder();
			a.writeString("parley");
			a.writeInt(minorVersion);
			boolean sequenced = minorVersion >= 1 && !c.sessionless;
			if( sequenced && sessionId == null ) {
				throw new IOException(what+": the session is closed");
			}
			if( sequenced ) {
				// SEQUENCE first: the session, the slot (0), its next sequence id, the highest slot, and no reply kept
				a.writeInt(c.count+1);
				a.writeInt(Nfs4.OP_SEQUENCE).writeFixedOpaque(sessionId).writeInt(slotSeq+1).writeInt(0).writeInt(0).writeBoolean(false);
			} else {
				a.writeInt(c.count);
			}
			a.write(c.ops);
			XdrDecoder r = rpc(Nfs4.PROC_COMPOUND, a);
			int status = r.readInt();
			r.readOpaque(1024);
			int count = r.readInt();
			if( sequenced && count > 0 ) {
				int op = r.readInt();
				int seqStatus = r.readInt();
				if( op != Nfs4.OP_SEQUENCE ) {
					throw new IOException("expected SEQUENCE, the server answered "+Nfs4.opName(op));
				}
				if( seqStatus != Nfs4.NFS4_OK ) {
					throw new NfsException(seqStatus, "sequence for "+what);
				}
				r.readFixedOpaque(Nfs4.NFS4_SESSIONID_SIZE);
				for (int i = 0; i < 5; i++) {
					r.readInt();
				}
				// the slot's request was run, so its sequence id is used, whatever the operations answered
				slotSeq++;
				count--;
			}
			boolean wait = status == Nfs4.NFS4ERR_DELAY || status == Nfs4.NFS4ERR_GRACE;
			if( status != Nfs4.NFS4_OK && count > 0 && !wait ) {
				return new Reply(r);
			}
			if( status == Nfs4.NFS4_OK ) {
				return new Reply(r);
			}
			if( wait && again && attempt < RETRIES ) {
				if( drainReturns() > 0 ) {
					// what the server waited for is back: try again at once
					continue;
				}
				try {
					Thread.sleep(RETRY_WAIT);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException(what+": interrupted");
				}
				continue;
			}
			throw new NfsException(status, what);
		}
	}

	private XdrDecoder rpc(int proc, XdrEncoder args) throws IOException {
		int x = xid++;
		NfsKerberos.Session gss = session;
		long seq = 0;
		XdrEncoder e;
		if( kerberos == null ) {
			e = new XdrEncoder();
			e.writeInt(x).writeInt(Rpc.CALL).writeInt(Rpc.RPC_VERSION).writeInt(Nfs4.PROGRAM).writeInt(Nfs4.VERSION).writeInt(proc);
			e.writeInt(Rpc.AUTH_SYS);
			e.writeOpaque(new AuthSys((int) (System.currentTimeMillis()/1000), machineName, uid, gid, new long[] {gid}).encode());
			e.writeInt(Rpc.AUTH_NONE).writeInt(0);
			e.write(args);
		} else {
			seq = gss.nextSeq();
			e = gss.dataCall(x, proc, args, seq);
		}
		XdrDecoder r = wire.exchange(x, e);
		if( r.readInt() != Rpc.MSG_ACCEPTED ) {
			if( gss != null ) {
				gss.denied(r);
			}
			throw new IOException("the server denied the call: "+r.readInt());
		}
		r.readInt();
		byte[] verifier = r.readOpaque(Rpc.MAX_AUTH_BYTES);
		int accept = r.readInt();
		if( accept != Rpc.SUCCESS ) {
			throw new IOException("the server didn't accept the call: accept_stat "+accept);
		}
		return gss == null ? r : gss.results(seq, verifier, r);
	}

	/** Calls and replies on the current connection. */
	private final NfsKerberos.Session.Wire wire = new NfsKerberos.Session.Wire() {
		@Override
		public int nextXid() {
			return xid++;
		}

		@Override
		public XdrDecoder exchange(int x, XdrEncoder call) throws IOException {
			ByteBuffer f = RecordMarkingDecoder.frame(call);
			byte[] b = new byte[f.remaining()];
			f.get(b);
			Link l = link;
			XdrDecoder r;
			if( l == null ) {
				synchronized (writeLock) {
					out.write(b);
					out.flush();
				}
				r = readRecord(in);
			} else {
				r = new XdrDecoder(exchangeOnLink(l, x, b));
			}
			if( r.readInt() != x ) {
				throw new IOException("the reply isn't for this call (xid)");
			}
			if( r.readInt() != Rpc.REPLY ) {
				throw new IOException("not an RPC reply");
			}
			return r;
		}
	};

	/** Send a call and wait for its reply, which the reader thread of the connection hands over. */
	private byte[] exchangeOnLink(Link l, int x, byte[] call) throws IOException {
		if( l.failed != null ) {
			throw new java.net.SocketException("the connection was lost: "+l.failed);
		}
		java.util.concurrent.CompletableFuture<byte[]> f = new java.util.concurrent.CompletableFuture<byte[]>();
		l.awaiting.put(x, f);
		try {
			synchronized (writeLock) {
				out.write(call);
				out.flush();
			}
			return f.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			throw new java.net.SocketTimeoutException("no answer in "+timeoutMillis+" ms");
		} catch (java.util.concurrent.ExecutionException e) {
			throw new java.net.SocketException("the connection was lost: "+e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		} finally {
			l.awaiting.remove(x);
		}
	}

	/**
	 * Read the connection in a thread of its own: a reply goes to the call that waits for it, and a call of the server
	 * (a callback over the session's back channel, RFC 8881 section 2.10.3.1) is answered.
	 */
	private Link startReader(DataInputStream input, OutputStream output) {
		Link l = new Link();
		Thread t = NamedExecutors.newThread("parley-nfs-client-reader", () -> {
			try {
				while( true ) {
					byte[] rec = readRecord(input).buffer().array();
					ByteBuffer b = ByteBuffer.wrap(rec);
					int x = b.getInt(0);
					int type = b.getInt(4);
					if( type == Rpc.REPLY ) {
						java.util.concurrent.CompletableFuture<byte[]> f = l.awaiting.remove(x);
						if( f != null ) {
							f.complete(rec);
						}
					} else if( type == Rpc.CALL && callbacks != null ) {
						XdrEncoder r = callbacks.answer(new XdrDecoder(rec));
						r.setInt(0, RecordMarkingDecoder.LAST_FRAGMENT | (r.size()-4));
						synchronized (writeLock) {
							output.write(r.array(), 0, r.size());
							output.flush();
						}
					}
				}
			} catch (IOException | RuntimeException e) {
				IOException failure = e instanceof IOException ? (IOException) e : new IOException(e);
				l.failed = failure;
				for (java.util.concurrent.CompletableFuture<byte[]> f : l.awaiting.values()) {
					f.completeExceptionally(failure);
				}
			}
		});
		t.start();
		return l;
	}

	private XdrDecoder readRecord(DataInputStream in) throws IOException {
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
}
