package us.bringardner.parley.nfs.client;

import java.io.Closeable;
import java.io.IOException;

/**
 * A byte range lock this client holds on a file of the server (D85). It is held until {@link #unlock()} or
 * {@link #close()}, or the client's close. Taken with {@link NfsClient#lock}.
 * <p>
 * When the connection is lost or the server restarts the client reclaims the lock in the server's grace period. If the
 * server refuses (it restarted without a grace period, as this package's own server does, or the lease had run out and
 * another client may have had the range) the lock is lost: {@link #isHeld()} is false, {@link #getLostReason()} says
 * why, and the handler given to {@link NfsClient#setLockLostHandler} is called.
 */
public final class NfsLock implements Closeable {

	/** The length of a lock that goes to the end of the file, however long it grows. */
	public static final long TO_END = -1L;

	private final NfsClient client;
	private final String path;
	private final long offset;
	private final long length;
	private final boolean write;

	// the state in the server, replaced when it is reclaimed; guarded by the client
	byte[] fh;
	int openSeqid;
	byte[] openOther;
	int openOwnerSeq;
	byte[] lockOther;
	int lockSeqid;
	final String lockOwner;
	final String openOwner;
	boolean lost;
	String lostReason;
	boolean released;

	NfsLock(NfsClient client, String path, long offset, long length, boolean write, String openOwner, String lockOwner) {
		this.client = client;
		this.path = path;
		this.offset = offset;
		this.length = length;
		this.write = write;
		this.openOwner = openOwner;
		this.lockOwner = lockOwner;
	}

	public String getPath() {
		return path;
	}

	public long getOffset() {
		return offset;
	}

	/** @return the length, or {@link #TO_END} */
	public long getLength() {
		return length;
	}

	public boolean isWrite() {
		return write;
	}

	/** @return true while the server has the lock for this client (false once it is released or lost) */
	public boolean isHeld() {
		synchronized (client) {
			return !lost && !released;
		}
	}

	/** @return why the lock was lost, or null when it was not */
	public String getLostReason() {
		synchronized (client) {
			return lostReason;
		}
	}

	/** Give the lock up. Nothing happens when it is released or lost already. */
	public void unlock() throws IOException {
		client.unlock(this);
	}

	@Override
	public void close() throws IOException {
		unlock();
	}

	@Override
	public String toString() {
		return (write ? "write" : "read")+" lock of "+path+" "+offset+"+"+(length == TO_END ? "end" : Long.toString(length));
	}
}
