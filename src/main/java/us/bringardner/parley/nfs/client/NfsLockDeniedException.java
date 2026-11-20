package us.bringardner.parley.nfs.client;

import us.bringardner.parley.nfs.protocol.Nfs4;

/** A byte range lock the server refused because another lock-owner holds a conflicting one (NFS4ERR_DENIED). */
public class NfsLockDeniedException extends NfsException {

	private static final long serialVersionUID = 1L;
	private final long offset;
	private final long length;
	private final boolean write;

	public NfsLockDeniedException(String what, long offset, long length, boolean write) {
		super(Nfs4.NFS4ERR_DENIED, what);
		this.offset = offset;
		this.length = length;
		this.write = write;
	}

	/** @return where the conflicting lock starts */
	public long getOffset() {
		return offset;
	}

	/** @return how long the conflicting lock is; {@link NfsLock#TO_END} (-1) when it goes to the end of the file */
	public long getLength() {
		return length;
	}

	/** @return true when the conflicting lock is a write lock */
	public boolean isWrite() {
		return write;
	}
}
