package us.bringardner.parley.nfs.client;

import java.io.Closeable;
import java.io.IOException;

/**
 * A file this client keeps open (D87): reads and writes at any offset without an open and close for each. If the
 * connection is made again (a lost connection, a restarted server) the file is opened again by its path, as the
 * state of the open is gone; byte range locks of the file are not part of this. Taken with {@link NfsClient#openFile}.
 */
public final class NfsOpenFile implements Closeable {

	private final NfsClient client;
	private final String path;
	private final boolean write;
	// guarded by the client
	NfsClient.OpenState state;
	boolean closed;

	NfsOpenFile(NfsClient client, String path, boolean write) {
		this.client = client;
		this.path = path;
		this.write = write;
	}

	public String getPath() {
		return path;
	}

	public boolean isWritable() {
		return write;
	}

	/** @return up to count bytes at offset (fewer, even none, at the end of the file) */
	public byte[] read(long offset, int count) throws IOException {
		return client.readFrom(this, offset, count);
	}

	/** Write the bytes at offset; they are stable on the server when this returns. */
	public void write(long offset, byte[] data, int off, int len) throws IOException {
		client.writeTo(this, offset, data, off, len);
	}

	/** @return the size of the file now, as the server says */
	public long size() throws IOException {
		return client.sizeOf(this);
	}

	/** Set the file's size (shorter, or longer with zeros). */
	public void truncate(long size) throws IOException {
		client.truncateOf(this, size);
	}

	@Override
	public void close() throws IOException {
		client.closeFile(this);
	}
}
