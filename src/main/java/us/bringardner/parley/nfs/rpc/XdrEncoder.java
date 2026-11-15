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
package us.bringardner.parley.nfs.rpc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes XDR (RFC 4506) items into a buffer that grows as needed.
 *
 * @author Tony Bringardner
 */
public class XdrEncoder {

	private byte[] buf;
	private int len;

	public XdrEncoder() {
		this(256);
	}

	public XdrEncoder(int initialSize) {
		buf = new byte[Math.max(16, initialSize)];
	}

	private void ensure(int n) {
		if( len+n > buf.length ) {
			buf = Arrays.copyOf(buf, Math.max(buf.length*2, len+n));
		}
	}

	/** @return the number of bytes written */
	public int size() {
		return len;
	}

	public XdrEncoder writeInt(int v) {
		ensure(4);
		buf[len++] = (byte) (v >>> 24);
		buf[len++] = (byte) (v >>> 16);
		buf[len++] = (byte) (v >>> 8);
		buf[len++] = (byte) v;
		return this;
	}

	/** Overwrite an int written earlier (e.g. a status or count only known later). */
	public void setInt(int position, int v) {
		buf[position] = (byte) (v >>> 24);
		buf[position+1] = (byte) (v >>> 16);
		buf[position+2] = (byte) (v >>> 8);
		buf[position+3] = (byte) v;
	}

	public XdrEncoder writeUnsignedInt(long v) {
		return writeInt((int) v);
	}

	public XdrEncoder writeLong(long v) {
		writeInt((int) (v >>> 32));
		return writeInt((int) v);
	}

	public XdrEncoder writeBoolean(boolean v) {
		return writeInt(v ? 1 : 0);
	}

	/** Fixed length opaque data: the bytes and padding, no length. */
	public XdrEncoder writeFixedOpaque(byte[] data) {
		return writeFixedOpaque(data, 0, data.length);
	}

	public XdrEncoder writeFixedOpaque(byte[] data, int off, int n) {
		int padded = XdrDecoder.pad(n);
		ensure(padded);
		System.arraycopy(data, off, buf, len, n);
		Arrays.fill(buf, len+n, len+padded, (byte) 0);
		len += padded;
		return this;
	}

	/** Variable length opaque data: the length, the bytes and padding. */
	public XdrEncoder writeOpaque(byte[] data) {
		writeInt(data.length);
		return writeFixedOpaque(data);
	}

	public XdrEncoder writeOpaque(byte[] data, int off, int n) {
		writeInt(n);
		return writeFixedOpaque(data, off, n);
	}

	public XdrEncoder writeString(String s) {
		return writeOpaque(s.getBytes(StandardCharsets.UTF_8));
	}

	public XdrEncoder writeIntArray(int[] values) {
		writeInt(values.length);
		for (int v : values) {
			writeInt(v);
		}
		return this;
	}

	/** Append what another encoder wrote. */
	public XdrEncoder write(XdrEncoder other) {
		ensure(other.len);
		System.arraycopy(other.buf, 0, buf, len, other.len);
		len += other.len;
		return this;
	}

	/** Append raw bytes (already XDR encoded). */
	public XdrEncoder writeRaw(byte[] data, int off, int n) {
		ensure(n);
		System.arraycopy(data, off, buf, len, n);
		len += n;
		return this;
	}

	/** Make room for n bytes the caller fills (e.g. READ data) and return the offset of the first one. */
	public int reserve(int n) {
		ensure(n);
		int ret = len;
		len += n;
		return ret;
	}

	/** Drop everything after the first n bytes (e.g. a READ that returned less than it reserved). */
	public void truncate(int n) {
		if( n < 0 || n > len ) {
			throw new IllegalArgumentException("Can't truncate "+len+" bytes to "+n);
		}
		len = n;
	}

	/** @return the backing array, valid from 0 to {@link #size()} until the next write */
	public byte[] array() {
		return buf;
	}

	public byte[] toByteArray() {
		return Arrays.copyOf(buf, len);
	}

	/** @return a buffer over the bytes written (shares the array) */
	public ByteBuffer toByteBuffer() {
		return ByteBuffer.wrap(buf, 0, len);
	}
}
