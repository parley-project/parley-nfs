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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.nfs.protocol.Nfs4;

/**
 * The random access streams READ and WRITE use, one per file, kept open between requests
 * and closed when the file's last open is closed, when the file is removed or renamed, or
 * when it hasn't been used for a while (decision D8).
 * <p>
 * A FileSource without random access is read with an input stream from the offset, and can
 * only be written at its end (appending); a WRITE anywhere else is NFS4ERR_NOTSUPP.
 * <p>
 * The stream may hold written data the FileSource hasn't seen yet, so the size of a file
 * with a stream comes from the stream ({@link #length(Node)}).
 *
 * @author Tony Bringardner
 */
public class OpenFiles {

	private static final class Handle {
		final IRandomAccessStream stream;
		final boolean writable;
		volatile long lastUse = System.currentTimeMillis();
		boolean closed;
		boolean dirty;

		Handle(IRandomAccessStream stream, boolean writable) {
			this.stream = stream;
			this.writable = writable;
		}
	}

	private final Map<Node, Handle> handles = new HashMap<Node, Handle>();

	private synchronized Handle cached(Node node) {
		return handles.get(node);
	}

	/**
	 * @return the node's stream, opened (or reopened for writing) if needed; null if the
	 * FileSource has no random access
	 */
	private Handle handle(Node node, FileSource file, boolean write) throws IOException {
		Handle h;
		synchronized (this) {
			h = handles.get(node);
			if( h != null && (h.writable || !write) ) {
				h.lastUse = System.currentTimeMillis();
				return h;
			}
		}
		if( h != null ) {
			release(node);
		}
		IRandomAccessStream s;
		try {
			s = file.getRandomAccessStream(write ? "rw" : "r");
		} catch (IOException e) {
			if( !file.exists() ) {
				throw e;
			}
			return null;
		}
		Handle nh = new Handle(s, write);
		Handle other;
		synchronized (this) {
			other = handles.get(node);
			if( other == null || (write && !other.writable) ) {
				handles.put(node, nh);
				other = null;
			}
		}
		if( other != null ) {
			s.close();
			return other;
		}
		return nh;
	}

	/**
	 * @return the bytes read into buf (0 at the end of the file)
	 */
	public int read(Node node, FileSource file, long offset, byte[] buf, int off, int count) throws IOException {
		for (int attempt = 0; attempt < 2; attempt++) {
			Handle h = handle(node, file, false);
			if( h == null ) {
				return readStream(file, offset, buf, off, count);
			}
			synchronized (h) {
				if( h.closed ) {
					continue;
				}
				long len = h.stream.length();
				if( offset >= len ) {
					return 0;
				}
				h.stream.seek(offset);
				int want = (int) Math.min(count, len-offset);
				int got = 0;
				while( got < want ) {
					int n = h.stream.read(buf, off+got, want-got);
					if( n <= 0 ) {
						break;
					}
					got += n;
				}
				return got;
			}
		}
		throw new IOException("The file was closed while it was read");
	}

	private static int readStream(FileSource file, long offset, byte[] buf, int off, int count) throws IOException {
		if( offset >= file.length() ) {
			return 0;
		}
		try (InputStream in = file.getInputStream(offset)) {
			int got = 0;
			while( got < count ) {
				int n = in.read(buf, off+got, count-got);
				if( n < 0 ) {
					break;
				}
				got += n;
			}
			return got;
		}
	}

	/**
	 * @param sync true to have the data on stable storage before returning
	 * @throws Nfs4Exception NFS4ERR_NOTSUPP for a write that isn't at the end of a file without random access
	 */
	public void write(Node node, FileSource file, long offset, byte[] buf, int off, int len, boolean sync)
			throws IOException, Nfs4Exception {
		for (int attempt = 0; attempt < 2; attempt++) {
			Handle h = handle(node, file, true);
			if( h == null ) {
				appendStream(file, offset, buf, off, len);
				return;
			}
			synchronized (h) {
				if( h.closed ) {
					continue;
				}
				h.stream.seek(offset);
				h.stream.write(buf, off, len);
				h.dirty = true;
				if( sync ) {
					h.stream.sync();
					h.dirty = false;
				}
				return;
			}
		}
		throw new IOException("The file was closed while it was written");
	}

	private static void appendStream(FileSource file, long offset, byte[] buf, int off, int len)
			throws IOException, Nfs4Exception {
		if( offset != file.length() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_NOTSUPP, file.getAbsolutePath()+" can only be written at its end");
		}
		try (OutputStream out = file.getOutputStream(true)) {
			out.write(buf, off, len);
		}
	}

	/**
	 * Set the file's size (SETATTR size, OPEN with size 0).
	 */
	public void setLength(Node node, FileSource file, long size) throws IOException, Nfs4Exception {
		for (int attempt = 0; attempt < 2; attempt++) {
			Handle h = handle(node, file, true);
			if( h == null ) {
				if( size == 0 ) {
					try (OutputStream out = file.getOutputStream(false)) {
						// truncated
					}
					return;
				}
				if( size == file.length() ) {
					return;
				}
				throw new Nfs4Exception(Nfs4.NFS4ERR_NOTSUPP, file.getAbsolutePath()+" can't be resized");
			}
			synchronized (h) {
				if( h.closed ) {
					continue;
				}
				h.stream.setLength(size);
				h.dirty = true;
				return;
			}
		}
		throw new IOException("The file was closed while it was resized");
	}

	/** COMMIT: what was written reaches stable storage. */
	public void commit(Node node) throws IOException {
		Handle h = cached(node);
		if( h == null ) {
			return;
		}
		synchronized (h) {
			if( !h.closed && h.dirty ) {
				h.stream.sync();
				h.dirty = false;
			}
		}
	}

	/** @return the size of a file with an open stream, null if it has none */
	public Long length(Node node) throws IOException {
		Handle h = cached(node);
		if( h == null ) {
			return null;
		}
		synchronized (h) {
			return h.closed ? null : h.stream.length();
		}
	}

	/** Write out and close the node's stream, if it has one. */
	public void release(Node node) throws IOException {
		Handle h;
		synchronized (this) {
			h = handles.remove(node);
		}
		if( h != null ) {
			close(h);
		}
	}

	private static void close(Handle h) throws IOException {
		synchronized (h) {
			if( h.closed ) {
				return;
			}
			h.closed = true;
			try {
				if( h.dirty ) {
					h.stream.sync();
				}
			} finally {
				h.stream.close();
			}
		}
	}

	/**
	 * Close the streams not used for idleMillis.
	 * @return the errors, if any
	 */
	public List<IOException> closeIdle(long now, long idleMillis) {
		List<Handle> idle = new ArrayList<Handle>();
		synchronized (this) {
			for (Map.Entry<Node, Handle> e : new ArrayList<Map.Entry<Node, Handle>>(handles.entrySet())) {
				if( now - e.getValue().lastUse > idleMillis ) {
					handles.remove(e.getKey());
					idle.add(e.getValue());
				}
			}
		}
		return closeAll(idle);
	}

	/** Close every stream (the server is stopping). */
	public List<IOException> closeAll() {
		List<Handle> all;
		synchronized (this) {
			all = new ArrayList<Handle>(handles.values());
			handles.clear();
		}
		return closeAll(all);
	}

	private static List<IOException> closeAll(List<Handle> list) {
		List<IOException> ret = new ArrayList<IOException>();
		for (Handle h : list) {
			try {
				close(h);
			} catch (IOException e) {
				ret.add(e);
			}
		}
		return ret;
	}

	public synchronized int size() {
		return handles.size();
	}
}
