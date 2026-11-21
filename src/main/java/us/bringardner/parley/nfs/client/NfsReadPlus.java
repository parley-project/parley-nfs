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

/**
 * What {@link NfsClient#readPlus} read: the bytes of the range (the holes as zeros), whether the end of the file was
 * reached, and how many of the bytes the server did not send because they were a hole.
 *
 * @since 2.0.0
 */
public final class NfsReadPlus {

	private final byte[] data;
	private final boolean eof;
	private final long holeBytes;

	NfsReadPlus(byte[] data, boolean eof, long holeBytes) {
		this.data = data;
		this.eof = eof;
		this.holeBytes = holeBytes;
	}

	/** @return the bytes read, the holes as zeros */
	public byte[] getData() {
		return data;
	}

	/** @return whether the read reached the end of the file */
	public boolean isEof() {
		return eof;
	}

	/** @return how many bytes of {@link #getData()} came as holes, and were not sent */
	public long getHoleBytes() {
		return holeBytes;
	}

	@Override
	public String toString() {
		return "NfsReadPlus["+data.length+" bytes, "+holeBytes+" in holes, eof="+eof+"]";
	}
}
