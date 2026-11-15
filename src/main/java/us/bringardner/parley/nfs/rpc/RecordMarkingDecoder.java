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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import us.bringardner.parley.net.nio.IFrameDecoder;

/**
 * ONC RPC record marking over TCP (RFC 5531 section 11): a record is one or more fragments,
 * each starting with a 4 byte header whose top bit marks the last fragment and whose other
 * 31 bits give the fragment's length. The frames are whole records, without the headers.
 * <p>
 * Fragments are added to the record as they arrive, so a record is never held twice.
 *
 * @author Tony Bringardner
 */
public class RecordMarkingDecoder implements IFrameDecoder {

	/** The top bit of a fragment header: this is the record's last fragment. */
	public static final int LAST_FRAGMENT = 0x80000000;
	/** Fragments one record may have; protects against a stream of empty fragments. */
	public static final int MAX_FRAGMENTS = 1024;

	private final int maxRecordSize;
	private byte[] record;
	private int recordLength;
	private int fragments;

	/**
	 * @param maxRecordSize the largest record accepted; a larger one is an error that closes the connection
	 */
	public RecordMarkingDecoder(int maxRecordSize) {
		if( maxRecordSize <= 0 ) {
			throw new IllegalArgumentException("maxRecordSize must be > 0");
		}
		this.maxRecordSize = maxRecordSize;
	}

	public int getMaxRecordSize() {
		return maxRecordSize;
	}

	@Override
	public ByteBuffer decode(ByteBuffer in) throws IOException {
		while( in.remaining() >= 4 ) {
			int pos = in.position();
			int header = in.getInt(pos);
			boolean last = (header & LAST_FRAGMENT) != 0;
			int len = header & ~LAST_FRAGMENT;
			if( (long) recordLength+len > maxRecordSize ) {
				reset();
				throw new IOException("RPC record larger than the maximum "+maxRecordSize);
			}
			if( in.remaining() < 4+len ) {
				return null;
			}
			if( ++fragments > MAX_FRAGMENTS ) {
				reset();
				throw new IOException("RPC record with more than "+MAX_FRAGMENTS+" fragments");
			}
			in.position(pos+4);
			if( last && record == null ) {
				// the usual case, one fragment
				fragments = 0;
				return IFrameDecoder.copy(in, len);
			}
			if( record == null ) {
				record = new byte[Math.max(len, 1024)];
			} else if( recordLength+len > record.length ) {
				record = Arrays.copyOf(record, Math.max(recordLength+len, Math.min(maxRecordSize, record.length*2)));
			}
			in.get(record, recordLength, len);
			recordLength += len;
			if( last ) {
				ByteBuffer ret = ByteBuffer.wrap(Arrays.copyOf(record, recordLength));
				reset();
				return ret;
			}
		}
		return null;
	}

	private void reset() {
		record = null;
		recordLength = 0;
		fragments = 0;
	}

	/**
	 * @return the record as one last fragment, ready to write
	 */
	public static ByteBuffer frame(byte[] record, int off, int len) {
		ByteBuffer ret = ByteBuffer.allocate(4+len);
		ret.putInt(LAST_FRAGMENT | len);
		ret.put(record, off, len);
		ret.flip();
		return ret;
	}

	/**
	 * @return what the encoder wrote as one last fragment, ready to write
	 */
	public static ByteBuffer frame(XdrEncoder record) {
		return frame(record.array(), 0, record.size());
	}
}
