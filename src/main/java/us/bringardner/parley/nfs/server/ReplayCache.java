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

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * The duplicate request cache: the replies of COMPOUNDs that changed something (CREATE,
 * REMOVE, RENAME, LINK, SETATTR), so a call the client sends again, usually on a new
 * connection after the old one broke, gets the same reply instead of running twice (a
 * CREATE retransmitted would otherwise be NFS4ERR_EXIST, a REMOVE NFS4ERR_NOENT).
 * <p>
 * A call is the same when it comes from the same address with the same xid and the same
 * bytes. Seqid operations (OPEN, CLOSE and the others) have their own replay of the owner's
 * last result. The oldest replies go when the cache is full.
 *
 * @author Tony Bringardner
 */
public class ReplayCache {

	private static final class Saved {
		final long checksum;
		final int length;
		final byte[] reply;

		Saved(long checksum, int length, byte[] reply) {
			this.checksum = checksum;
			this.length = length;
			this.reply = reply;
		}
	}

	private final int size;
	private final Map<String, Saved> entries;

	/**
	 * @param size the most replies kept
	 */
	public ReplayCache(int size) {
		this.size = size;
		this.entries = new LinkedHashMap<String, Saved>(16, 0.75f, true) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Saved> eldest) {
				return size() > ReplayCache.this.size;
			}
		};
	}

	public int getSize() {
		return size;
	}

	private static String key(InetAddress client, int xid) {
		return (client == null ? "" : client.getHostAddress())+"/"+xid;
	}

	private static long checksum(ByteBuffer call) {
		CRC32C crc = new CRC32C();
		crc.update(call.duplicate());
		return crc.getValue();
	}

	/**
	 * @param call the whole call record
	 * @return the saved reply (record marking header included) of the same call, or null
	 */
	public byte[] find(InetAddress client, int xid, ByteBuffer call) {
		Saved e;
		synchronized (entries) {
			e = entries.get(key(client, xid));
		}
		if( e == null || e.length != call.remaining() || e.checksum != checksum(call) ) {
			return null;
		}
		return e.reply.clone();
	}

	/**
	 * Save a reply.
	 * @param call the whole call record
	 * @param reply the reply, record marking header included
	 */
	public void save(InetAddress client, int xid, ByteBuffer call, byte[] reply) {
		Saved e = new Saved(checksum(call), call.remaining(), reply);
		synchronized (entries) {
			entries.put(key(client, xid), e);
		}
	}

	public int getCount() {
		synchronized (entries) {
			return entries.size();
		}
	}
}
