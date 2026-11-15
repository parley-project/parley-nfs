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
package us.bringardner.parley.nfs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.nfs.rpc.RecordMarkingDecoder;
import us.bringardner.parley.nfs.rpc.XdrDecoder;
import us.bringardner.parley.nfs.rpc.XdrEncoder;
import us.bringardner.parley.nfs.rpc.XdrException;
import us.bringardner.parley.nfs.server.Node;
import us.bringardner.parley.nfs.server.NodeTable;

/**
 * XDR, record marking and the node table.
 */
public class XdrTest {

	@Test
	public void roundTrip() throws Exception {
		XdrEncoder e = new XdrEncoder(16);
		e.writeInt(-5).writeUnsignedInt(0xfffffffeL).writeLong(Long.MIN_VALUE).writeBoolean(true)
			.writeOpaque(new byte[] {1, 2, 3}).writeString("héllo").writeIntArray(new int[] {7, 8})
			.writeFixedOpaque(new byte[] {9});
		assertEquals(0, e.size() % 4);
		XdrDecoder d = new XdrDecoder(e.toByteArray());
		assertEquals(-5, d.readInt());
		assertEquals(0xfffffffeL, d.readUnsignedInt());
		assertEquals(Long.MIN_VALUE, d.readLong());
		assertTrue(d.readBoolean());
		assertArrayEquals(new byte[] {1, 2, 3}, d.readOpaque(10));
		assertEquals("héllo", d.readString(10));
		assertArrayEquals(new int[] {7, 8}, d.readIntArray(2));
		assertArrayEquals(new byte[] {9}, d.readFixedOpaque(1));
		assertEquals(0, d.remaining());
	}

	@Test
	public void badInput() {
		assertThrows(XdrException.class, () -> new XdrDecoder(new byte[3]).readInt());
		assertThrows(XdrException.class, () -> new XdrDecoder(new XdrEncoder().writeInt(2).toByteArray()).readBoolean());
		// a length larger than allowed, or than what is there
		assertThrows(XdrException.class, () -> new XdrDecoder(new XdrEncoder().writeInt(11).toByteArray()).readOpaque(10));
		assertThrows(XdrException.class, () -> new XdrDecoder(new XdrEncoder().writeInt(8).toByteArray()).readOpaque(10));
		assertThrows(XdrDecoder.InvalidUtf8Exception.class,
				() -> new XdrDecoder(new XdrEncoder().writeOpaque(new byte[] {(byte) 0xc3}).toByteArray()).readString(10));
	}

	@Test
	public void recordMarking() throws Exception {
		RecordMarkingDecoder d = new RecordMarkingDecoder(100);
		ByteBuffer in = ByteBuffer.allocate(64);
		in.putInt(3).put(new byte[] {1, 2, 3});
		in.putInt(RecordMarkingDecoder.LAST_FRAGMENT | 2).put((byte) 4);
		in.flip();
		// the last fragment isn't all there: the first one is taken, no record yet
		assertNull(d.decode(in));
		ByteBuffer more = ByteBuffer.allocate(64);
		more.put(in);
		more.put((byte) 5);
		more.flip();
		ByteBuffer rec = d.decode(more);
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, toArray(rec));
		assertEquals(0, more.remaining());

		ByteBuffer big = ByteBuffer.allocate(8);
		big.putInt(RecordMarkingDecoder.LAST_FRAGMENT | 101).flip();
		assertThrows(IOException.class, () -> d.decode(big));
	}

	private static byte[] toArray(ByteBuffer b) {
		byte[] ret = new byte[b.remaining()];
		b.get(ret);
		return ret;
	}

	@Test
	public void nodesKeepTheirIdsWhenRenamed() {
		NodeTable t = new NodeTable();
		Node dir = t.get("a");
		Node file = t.get("a/f");
		Node other = t.get("b");
		long id = file.getFileid();
		t.renamed("a", "c");
		assertEquals("c/f", file.getPath());
		assertEquals("c", dir.getPath());
		assertSame(file, t.find(id));
		assertSame(file, t.findPath("c/f"));
		assertNull(t.findPath("a/f"));
		// replacing b forgets the node that was there
		t.renamed("c/f", "b");
		assertSame(file, t.findPath("b"));
		assertNull(t.find(other.getFileid()));
		// the same path gets the same fileid in another table (after a restart)
		assertEquals(new NodeTable().get("x/y").getFileid(), t.get("x/y").getFileid());
	}
}
