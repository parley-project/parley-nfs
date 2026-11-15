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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads XDR (RFC 4506) items from a buffer, big endian, each padded to a multiple of 4 bytes.
 * <p>
 * Every read checks the bytes are there, so a short or malformed message is an
 * {@link XdrException}, never a buffer exception.
 *
 * @author Tony Bringardner
 */
public class XdrDecoder {

	private final ByteBuffer in;

	/**
	 * @param in the bytes, from its position to its limit; reads move the position
	 */
	public XdrDecoder(ByteBuffer in) {
		this.in = in;
	}

	public XdrDecoder(byte[] in) {
		this(ByteBuffer.wrap(in));
	}

	/** @return the bytes not read yet */
	public int remaining() {
		return in.remaining();
	}

	/** @return the position in the buffer */
	public int position() {
		return in.position();
	}

	public ByteBuffer buffer() {
		return in;
	}

	private void need(int n) throws XdrException {
		if( n < 0 || in.remaining() < n ) {
			throw new XdrException("Need "+n+" bytes, only "+in.remaining()+" left");
		}
	}

	public int readInt() throws XdrException {
		need(4);
		return in.getInt();
	}

	/** @return an unsigned int as a long */
	public long readUnsignedInt() throws XdrException {
		return readInt() & 0xffffffffL;
	}

	/** A hyper (signed or unsigned, the caller decides what the 64 bits mean). */
	public long readLong() throws XdrException {
		need(8);
		return in.getLong();
	}

	public boolean readBoolean() throws XdrException {
		int v = readInt();
		if( v != 0 && v != 1 ) {
			throw new XdrException("Not a boolean: "+v);
		}
		return v == 1;
	}

	/**
	 * A length, checked against the maximum the protocol allows and the bytes left.
	 */
	private int readLength(int max) throws XdrException {
		long len = readUnsignedInt();
		if( len > max ) {
			throw new XdrException("Length "+len+" is larger than "+max);
		}
		return (int) len;
	}

	/** Fixed length opaque data (opaque x[n]). */
	public byte[] readFixedOpaque(int len) throws XdrException {
		int padded = pad(len);
		need(padded);
		byte[] ret = new byte[len];
		in.get(ret);
		in.position(in.position()+padded-len);
		return ret;
	}

	/** Variable length opaque data (opaque x&lt;max&gt;). */
	public byte[] readOpaque(int max) throws XdrException {
		return readFixedOpaque(readLength(max));
	}

	/**
	 * A string as UTF-8, invalid UTF-8 is an error (NFSv4 utf8str: the caller maps it to NFS4ERR_INVAL).
	 */
	public String readString(int max) throws XdrException {
		byte[] b = readOpaque(max);
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(b)).toString();
		} catch (CharacterCodingException e) {
			throw new InvalidUtf8Exception();
		}
	}

	/** An array of unsigned ints with a count (e.g. bitmap4). */
	public int[] readIntArray(int max) throws XdrException {
		int len = readLength(max);
		need(len*4);
		int[] ret = new int[len];
		for (int i = 0; i < len; i++) {
			ret[i] = in.getInt();
		}
		return ret;
	}

	/** Skip n bytes plus padding. */
	public void skip(int n) throws XdrException {
		int padded = pad(n);
		need(padded);
		in.position(in.position()+padded);
	}

	/** @return n rounded up to a multiple of 4 */
	public static int pad(int n) {
		return (n+3) & ~3;
	}

	/** A string that isn't valid UTF-8. */
	public static class InvalidUtf8Exception extends XdrException {
		private static final long serialVersionUID = 1L;

		public InvalidUtf8Exception() {
			super("Invalid UTF-8");
		}
	}
}
