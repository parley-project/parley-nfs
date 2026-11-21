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

import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.server.StateManager.ClientRecord;

/**
 * An NFSv4.1 session (RFC 8881 section 2.10): the slots that give each request exactly-once behavior. A slot
 * runs one request at a time, in the order of its sequence ids; a request sent again (same slot, same
 * sequence id) gets the reply the slot kept, if the client asked for it to be kept.
 *
 * @author Tony Bringardner
 */
public final class Session {

	/** One slot of the session's table. */
	private static final class Slot {
		int seq;
		boolean busy;
		/** the whole COMPOUND4res of the last request, or null if it was not kept */
		byte[] reply;
	}

	/** What a client and the server agree a channel carries (channel_attrs4, RFC 8881 section 18.36). */
	public static final class Channel {
		public int headerPad;
		public int maxRequest;
		public int maxResponse;
		public int maxCached;
		public int maxOperations;
		public int maxRequests;
	}

	private final byte[] id;
	private final String key;
	private final ClientRecord client;
	private final Slot[] slots;
	private final Channel fore;
	private final Channel back;
	private final int flags;
	private final long cbProgram;

	Session(byte[] id, ClientRecord client, Channel fore, Channel back, int flags, long cbProgram) {
		this.id = id;
		this.key = key(id);
		this.client = client;
		this.fore = fore;
		this.back = back;
		this.flags = flags;
		this.cbProgram = cbProgram;
		this.slots = new Slot[fore.maxRequests];
		for (int i = 0; i < slots.length; i++) {
			slots[i] = new Slot();
		}
	}

	static String key(byte[] id) {
		return new String(id, java.nio.charset.StandardCharsets.ISO_8859_1);
	}

	public byte[] getId() {
		return id.clone();
	}

	String getKey() {
		return key;
	}

	public ClientRecord getClient() {
		return client;
	}

	public Channel getFore() {
		return fore;
	}

	public Channel getBack() {
		return back;
	}

	public int getFlags() {
		return flags;
	}

	public long getCbProgram() {
		return cbProgram;
	}

	public int getSlotCount() {
		return slots.length;
	}

	/** The slot of a SEQUENCE and what it says about the request. */
	public static final class Use {
		/** the saved reply when the request is one sent again, else null */
		public final byte[] replay;
		public final int slot;

		Use(byte[] replay, int slot) {
			this.replay = replay;
			this.slot = slot;
		}
	}

	/**
	 * Start a request in a slot (RFC 8881 section 2.10.6.1).
	 * @return the saved reply of a request that is sent again, or a {@link Use} with no replay if it is a new one
	 * (the slot is busy until {@link #finish})
	 * @throws Nfs4Exception NFS4ERR_BADSLOT, NFS4ERR_BAD_HIGH_SLOT, NFS4ERR_SEQ_MISORDERED, NFS4ERR_DELAY (the
	 * slot's last request is still running) or NFS4ERR_RETRY_UNCACHED_REP
	 */
	public Use start(int slotid, int seq, int highest) throws Nfs4Exception {
		if( slotid < 0 || slotid >= slots.length ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BADSLOT);
		}
		if( highest < 0 || highest >= slots.length ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_HIGH_SLOT);
		}
		Slot s = slots[slotid];
		synchronized (s) {
			if( seq == s.seq && s.seq != 0 ) {
				if( s.busy ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_DELAY, "the request in slot "+slotid+" is still running");
				}
				if( s.reply == null ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_RETRY_UNCACHED_REP);
				}
				return new Use(s.reply, slotid);
			}
			if( seq != s.seq+1 || s.busy ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_SEQ_MISORDERED, "slot "+slotid+" is at "+s.seq+", the request has "+seq);
			}
			s.seq = seq;
			s.busy = true;
			s.reply = null;
			return new Use(null, slotid);
		}
	}

	/**
	 * The request in a slot is done.
	 * @param reply its COMPOUND4res to keep for a request sent again, or null not to keep it
	 */
	public void finish(int slotid, byte[] reply) {
		Slot s = slots[slotid];
		synchronized (s) {
			s.busy = false;
			s.reply = reply;
		}
	}

	@Override
	public String toString() {
		return Long.toHexString(client.getClientid())+"/"+key.length();
	}
}
