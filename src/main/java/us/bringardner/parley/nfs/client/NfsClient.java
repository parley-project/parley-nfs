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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

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
			Nfs4.FATTR4_MODE, Nfs4.FATTR4_TIME_MODIFY);
	private static final byte[] NO_STATE = new byte[12];
	private static final int RETRIES = 10;
	private static final long RETRY_WAIT = 500;

	private final Socket socket;
	private final DataInputStream in;
	private final OutputStream out;
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
		socket = new Socket();
		try {
			socket.connect(new InetSocketAddress(host, port), timeoutMillis);
			socket.setSoTimeout(timeoutMillis);
			in = new DataInputStream(socket.getInputStream());
			out = socket.getOutputStream();
			setClientId();
			mount(mount);
			startRenewing();
		} catch (IOException | RuntimeException e) {
			close();
			throw e;
		}
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
		socket.close();
	}

	// ------------------------------------------------------------------ files

	/**
	 * @param path a path below the mount ("" or "/" is the mount itself)
	 * @return what the server says about it
	 * @throws NfsException with isNotFound() when it doesn't exist
	 */
	public synchronized NfsFileInfo info(String path) throws IOException {
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

	/**
	 * Read up to count bytes of a file.
	 * @return the bytes (fewer, even none, at the end of the file)
	 */
	public synchronized byte[] read(String path, long offset, int count) throws IOException {
		Opened o = open(path, Nfs4.OPEN4_SHARE_ACCESS_READ, false);
		try {
			return readOpen(o, offset, count);
		} finally {
			closeOpen(o);
		}
	}

	/** @return the whole file */
	public synchronized byte[] readAll(String path) throws IOException {
		Opened o = open(path, Nfs4.OPEN4_SHARE_ACCESS_READ, false);
		try {
			java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
			long offset = 0;
			while( true ) {
				byte[] b = readOpen(o, offset, CHUNK);
				if( b.length == 0 ) {
					return all.toByteArray();
				}
				all.write(b);
				offset += b.length;
			}
		} finally {
			closeOpen(o);
		}
	}

	/**
	 * Write bytes at an offset, making the file when it isn't there. The write is stable on the
	 * server when this returns. What is already in the file beyond the bytes written stays.
	 */
	public synchronized void write(String path, long offset, byte[] data) throws IOException {
		Opened o = open(path, Nfs4.OPEN4_SHARE_ACCESS_WRITE, true);
		try {
			int done = 0;
			while( done < data.length || data.length == 0 && done == 0 ) {
				int n = Math.min(CHUNK, data.length-done);
				Compound c = new Compound().putfh(o.fh);
				XdrEncoder a = c.op(Nfs4.OP_WRITE).args;
				a.writeInt(o.seqid).writeFixedOpaque(o.other);
				a.writeLong(offset+done).writeInt(Nfs4.FILE_SYNC4).writeOpaque(data, done, n);
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
		} finally {
			closeOpen(o);
		}
	}

	// ------------------------------------------------------------------ opens

	private static final class Opened {
		byte[] fh;
		int seqid;
		byte[] other;
		String owner;
		int ownerSeq;
	}

	private Opened open(String path, int access, boolean create) throws IOException {
		Path p = split(path);
		Opened o = new Opened();
		o.owner = ownerPrefix+"-"+(openCount++);
		Compound c = new Compound();
		lookupParent(c, p);
		XdrEncoder a = c.op(Nfs4.OP_OPEN).args;
		a.writeInt(o.ownerSeq++).writeInt(access).writeInt(Nfs4.OPEN4_SHARE_DENY_NONE).writeLong(clientId)
			.writeOpaque(o.owner.getBytes(StandardCharsets.UTF_8));
		if( create ) {
			a.writeInt(Nfs4.OPEN4_CREATE).writeInt(Nfs4.UNCHECKED4).writeIntArray(bitmap(Nfs4.FATTR4_MODE)).writeInt(4).writeInt(FILE_MODE);
		} else {
			a.writeInt(Nfs4.OPEN4_NOCREATE);
		}
		a.writeInt(Nfs4.CLAIM_NULL).writeString(p.name);
		c.op(Nfs4.OP_GETFH);
		Reply r = call(c, "open "+path);
		skipLookup(r, p.parents);
		r.ok(Nfs4.OP_OPEN);
		o.seqid = r.in.readInt();
		o.other = r.in.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
		r.in.readBoolean();
		r.in.readLong();
		r.in.readLong();
		int rflags = r.in.readInt();
		r.in.readIntArray(8);
		int delegation = r.in.readInt();
		if( delegation != Nfs4.OPEN_DELEGATE_NONE ) {
			throw new IOException("open "+path+": the server gave a delegation this client doesn't ask for");
		}
		r.ok(Nfs4.OP_GETFH);
		o.fh = r.in.readOpaque(Nfs4.NFS4_FHSIZE);
		if( (rflags & Nfs4.OPEN4_RESULT_CONFIRM) != 0 ) {
			Compound cc = new Compound().putfh(o.fh);
			cc.op(Nfs4.OP_OPEN_CONFIRM).args.writeInt(o.seqid).writeFixedOpaque(o.other).writeInt(o.ownerSeq++);
			Reply cr = call(cc, "confirm "+path);
			cr.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_OPEN_CONFIRM);
			o.seqid = cr.in.readInt();
			o.other = cr.in.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
		}
		return o;
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
		Reply r = call(c, "close");
		r.ok(Nfs4.OP_PUTFH).ok(Nfs4.OP_CLOSE);
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
				case Nfs4.FATTR4_TIME_MODIFY:
					long sec = a.readLong();
					long nsec = a.readUnsignedInt();
					time = sec*1000+nsec/1000000;
					break;
				default:
					throw new IOException("the server sent attribute "+(w*32+b)+" that was not asked for");
				}
			}
		}
		return new NfsFileInfo(name, fh, type, size, fileId, mode, time);
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
		byte[] verifier = new byte[Nfs4.NFS4_VERIFIER_SIZE];
		new SecureRandom().nextBytes(verifier);
		Compound c = new Compound();
		XdrEncoder a = c.op(Nfs4.OP_SETCLIENTID).args;
		a.writeFixedOpaque(verifier).writeOpaque(ownerPrefix.getBytes(StandardCharsets.UTF_8));
		// no callback: the server can't call this client, so it gives no delegations
		a.writeInt(0).writeString("tcp").writeString("0.0.0.0.0.0").writeInt(0);
		Reply r = call(c, "setclientid");
		r.ok(Nfs4.OP_SETCLIENTID);
		clientId = r.in.readLong();
		byte[] confirm = r.in.readFixedOpaque(Nfs4.NFS4_VERIFIER_SIZE);
		Compound cc = new Compound();
		cc.op(Nfs4.OP_SETCLIENTID_CONFIRM).args.writeLong(clientId).writeFixedOpaque(confirm);
		call(cc, "setclientid_confirm").ok(Nfs4.OP_SETCLIENTID_CONFIRM);
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
		renewer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "parley-nfs-client-renew");
				t.setDaemon(true);
				return t;
			}
		});
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
			c.op(Nfs4.OP_RENEW).args.writeLong(clientId);
			call(c, "renew").ok(Nfs4.OP_RENEW);
		} catch (IOException | RuntimeException e) {
			// the next call of the client reports a connection that is gone
		}
	}

	// ------------------------------------------------------------------ RPC

	private static final class Compound {
		final XdrEncoder ops = new XdrEncoder();
		XdrEncoder args;
		int count;

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
		for (int attempt = 0; ; attempt++) {
			XdrEncoder a = new XdrEncoder();
			a.writeString("parley");
			a.writeInt(0);
			a.writeInt(c.count);
			a.write(c.ops);
			XdrDecoder r = rpc(Nfs4.PROC_COMPOUND, a);
			int status = r.readInt();
			r.readOpaque(1024);
			int count = r.readInt();
			boolean wait = status == Nfs4.NFS4ERR_DELAY || status == Nfs4.NFS4ERR_GRACE;
			if( status != Nfs4.NFS4_OK && count > 0 && !wait ) {
				return new Reply(r);
			}
			if( status == Nfs4.NFS4_OK ) {
				return new Reply(r);
			}
			if( wait && attempt < RETRIES ) {
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
		XdrEncoder e = new XdrEncoder();
		e.writeInt(x).writeInt(Rpc.CALL).writeInt(Rpc.RPC_VERSION).writeInt(Nfs4.PROGRAM).writeInt(Nfs4.VERSION).writeInt(proc);
		e.writeInt(Rpc.AUTH_SYS);
		e.writeOpaque(new AuthSys((int) (System.currentTimeMillis()/1000), machineName, uid, gid, new long[] {gid}).encode());
		e.writeInt(Rpc.AUTH_NONE).writeInt(0);
		e.write(args);
		ByteBuffer f = RecordMarkingDecoder.frame(e);
		byte[] b = new byte[f.remaining()];
		f.get(b);
		out.write(b);
		out.flush();
		XdrDecoder r = readRecord();
		if( r.readInt() != x ) {
			throw new IOException("the reply isn't for this call (xid)");
		}
		if( r.readInt() != Rpc.REPLY ) {
			throw new IOException("not an RPC reply");
		}
		if( r.readInt() != Rpc.MSG_ACCEPTED ) {
			throw new IOException("the server denied the call: "+r.readInt());
		}
		r.readInt();
		r.readOpaque(Rpc.MAX_AUTH_BYTES);
		int accept = r.readInt();
		if( accept != Rpc.SUCCESS ) {
			throw new IOException("the server didn't accept the call: accept_stat "+accept);
		}
		return r;
	}

	private XdrDecoder readRecord() throws IOException {
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
