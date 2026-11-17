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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import us.bringardner.parley.nfs.protocol.Nfs4;

/**
 * Client, open and lock state (RFC 7530 sections 9 and 16): client IDs and their leases,
 * open-owners and lock-owners and their sequence numbers, opens and their share reservations,
 * and byte-range locks (decisions D17 to D22: kept here only, advisory, no waiting).
 * <p>
 * Nothing is kept on disk (decision D7): after a restart every client ID and stateid is stale,
 * clients start over with SETCLIENTID, and there is no grace period, so an open can't be
 * reclaimed (CLAIM_PREVIOUS gets NFS4ERR_NO_GRACE).
 *
 * @author Tony Bringardner
 */
public class StateManager {

	/** Expired client IDs remembered, so their stateids get NFS4ERR_EXPIRED rather than NFS4ERR_BAD_STATEID. */
	private static final int EXPIRED_REMEMBERED = 1024;

	private final int boot;
	private final long leaseMillis;
	private final SecureRandom random = new SecureRandom();
	private final AtomicInteger clientSeq = new AtomicInteger();
	private final AtomicInteger stateSeq = new AtomicInteger();

	private final Map<Long, ClientRecord> confirmedById = new HashMap<Long, ClientRecord>();
	private final Map<String, ClientRecord> confirmedByName = new HashMap<String, ClientRecord>();
	private final Map<Long, ClientRecord> unconfirmedById = new HashMap<Long, ClientRecord>();
	private final Map<String, ClientRecord> unconfirmedByName = new HashMap<String, ClientRecord>();
	private final Map<String, OpenState> states = new HashMap<String, OpenState>();
	private final Map<String, LockState> lockStates = new HashMap<String, LockState>();
	private final Map<String, DelegationState> delegationStates = new HashMap<String, DelegationState>();
	/** Delegations taken back after the lease ran out, so their stateids get NFS4ERR_DELEG_REVOKED */
	private final Set<String> revokedDelegations = new LinkedHashSet<String>();
	private final Set<Integer> expired = new LinkedHashSet<Integer>();

	/**
	 * @param boot identifies this run of the server (seconds since the epoch at start); client IDs
	 * and stateids from another run are stale
	 * @param leaseMillis the lease time
	 */
	public StateManager(int boot, long leaseMillis) {
		this.boot = boot;
		this.leaseMillis = leaseMillis;
	}

	public long getLeaseMillis() {
		return leaseMillis;
	}

	public int getBoot() {
		return boot;
	}

	// ================================================================== records

	/** A client (nfs_client_id4) and its client ID. */
	public static final class ClientRecord {
		final long clientid;
		final byte[] verifier;
		final String name;
		final String principal;
		volatile byte[] confirmVerifier;
		volatile String cbNetid;
		volatile String cbAddr;
		volatile long cbProgram;
		volatile int cbIdent;
		/** where SETCLIENTID came from: the only host a callback address may name (D56) */
		volatile java.net.InetAddress cbFrom;
		/** true once a CB_NULL to the callback address was answered */
		volatile boolean cbUp;
		volatile boolean confirmed;
		volatile long lastRenew;
		/** open-owners by owner, guarded by the StateManager */
		Map<String, OpenOwner> owners = new HashMap<String, OpenOwner>();
		/** lock-owners by owner, guarded by the StateManager */
		Map<String, LockOwner> lockOwners = new HashMap<String, LockOwner>();
		/** the delegations of this client, guarded by the StateManager */
		final Set<DelegationState> delegations = new LinkedHashSet<DelegationState>();

		ClientRecord(long clientid, byte[] verifier, String name, String principal, byte[] confirmVerifier,
				String cbNetid, String cbAddr) {
			this.clientid = clientid;
			this.verifier = verifier;
			this.name = name;
			this.principal = principal;
			this.confirmVerifier = confirmVerifier;
			this.cbNetid = cbNetid;
			this.cbAddr = cbAddr;
			this.lastRenew = System.currentTimeMillis();
		}

		public long getClientid() {
			return clientid;
		}

		public byte[] getConfirmVerifier() {
			return confirmVerifier.clone();
		}

		@Override
		public String toString() {
			return Long.toHexString(clientid);
		}
	}

	/**
	 * An owner whose operations are sequenced by its seqid (RFC 7530 section 9.1.7): an
	 * open-owner or a lock-owner.
	 */
	public abstract static class SeqOwner {
		final ClientRecord client;
		final String key;
		int seqid;
		/** The last seqid operation's result (status and body), for a retransmission. */
		byte[] lastResult;
		int lastOp;
		Node lastFh;

		SeqOwner(ClientRecord client, String key, int seqid) {
			this.client = client;
			this.key = key;
			this.seqid = seqid-1;
		}

		/**
		 * @return true if seqid is a retransmission of the last operation (its result is in
		 * {@link #getLastResult()}), false if it is the next one
		 * @throws Nfs4Exception NFS4ERR_BAD_SEQID otherwise
		 */
		public boolean checkSeqid(int seqid, int op) throws Nfs4Exception {
			if( seqid == this.seqid+1 ) {
				return false;
			}
			if( seqid == this.seqid && lastResult != null && lastOp == op ) {
				return true;
			}
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_SEQID, "seqid "+seqid+", expected "+(this.seqid+1));
		}

		/** The operation with seqid is done: remember its result for a retransmission. */
		public void done(int seqid, int op, byte[] result, Node fh) {
			this.seqid = seqid;
			this.lastOp = op;
			this.lastResult = result;
			this.lastFh = fh;
		}

		public byte[] getLastResult() {
			return lastResult;
		}

		public Node getLastFh() {
			return lastFh;
		}

		public ClientRecord getClient() {
			return client;
		}

		/** @return the owner as LOCK4denied and LOCKT name it: its bytes */
		public byte[] getOwnerBytes() {
			return key.getBytes(StandardCharsets.ISO_8859_1);
		}
	}

	/** An open-owner: the opens it makes are sequenced by its seqid. */
	public static final class OpenOwner extends SeqOwner {
		boolean confirmed;
		/** An open closed by the last operation, kept so a retransmitted CLOSE finds it. */
		OpenState lastClosed;
		final Set<OpenState> opens = new LinkedHashSet<OpenState>();

		OpenOwner(ClientRecord client, String key, int seqid) {
			super(client, key, seqid);
		}

		public boolean isConfirmed() {
			return confirmed;
		}
	}

	/** A lock-owner (decision D17): its byte-range locks, one lock state per file. */
	public static final class LockOwner extends SeqOwner {
		final Map<Node, LockState> states = new HashMap<Node, LockState>();

		LockOwner(ClientRecord client, String key, int seqid) {
			super(client, key, seqid);
		}

		boolean holdsLocks() {
			for (LockState s : states.values()) {
				if( !s.ranges.isEmpty() ) {
					return true;
				}
			}
			return false;
		}
	}

	/** A locked byte range, from start up to (not including) end; end -1 is the largest offset. */
	static final class Range {
		final long start;
		final long end;
		final boolean write;

		Range(long start, long end, boolean write) {
			this.start = start;
			this.end = end;
			this.write = write;
		}

		boolean overlaps(long s, long e) {
			return Long.compareUnsigned(start, e) < 0 && Long.compareUnsigned(s, end) < 0;
		}

		/** @return the length as LOCK4denied gives it: all ones to the end of the file */
		long length() {
			return end == -1L ? -1L : end-start;
		}
	}

	/** A lock-owner's locks on one file, under one open; its stateid is the lock stateid. */
	public static final class LockState {
		final byte[] other;
		final LockOwner owner;
		final OpenState open;
		final Node node;
		volatile int seqid;
		boolean closed;
		/** sorted by start, never overlapping (guarded by the StateManager) */
		final List<Range> ranges = new ArrayList<Range>();

		LockState(byte[] other, LockOwner owner, OpenState open, Node node) {
			this.other = other;
			this.owner = owner;
			this.open = open;
			this.node = node;
		}

		public LockOwner getOwner() {
			return owner;
		}

		public OpenState getOpen() {
			return open;
		}

		public StateId stateId() {
			return new StateId(seqid, other);
		}
	}

	/** NFS4ERR_DENIED, with the lock that conflicts (LOCK4denied). */
	public static class LockDeniedException extends Nfs4Exception {
		private static final long serialVersionUID = 1L;
		public final long offset;
		public final long length;
		public final boolean write;
		public final long clientid;
		public final byte[] owner;

		LockDeniedException(Range r, SeqOwner holder) {
			super(Nfs4.NFS4ERR_DENIED);
			this.offset = r.start;
			this.length = r.length();
			this.write = r.write;
			this.clientid = holder.client.clientid;
			this.owner = holder.getOwnerBytes();
		}
	}

	/** An open: an open-owner's share reservation on a file. */
	public static final class OpenState {
		final byte[] other;
		final OpenOwner owner;
		final Node node;
		volatile int seqid = 1;
		volatile int access;
		volatile int deny;
		volatile boolean closed;
		/**
		 * The share access and deny pairs an OPEN_DOWNGRADE may ask for: the unions of the pairs of
		 * some of the OPENs in effect (RFC 7530 section 16.19.4). Bit {@code access | deny<<2} is
		 * set for each pair.
		 */
		int reachable;

		OpenState(byte[] other, OpenOwner owner, Node node, int access, int deny) {
			this.other = other;
			this.owner = owner;
			this.node = node;
			this.access = access;
			this.deny = deny;
			opened(access, deny);
		}

		/** An OPEN with this pair is in effect: it and its unions with the others can be downgraded to. */
		void opened(int access, int deny) {
			int p = access | deny<<2;
			int r = reachable | 1 << p;
			for (int q = 0; q < 16; q++) {
				if( (reachable & 1 << q) != 0 ) {
					r |= 1 << (q | p);
				}
			}
			reachable = r;
		}

		/** @return true if an OPEN_DOWNGRADE may ask for this pair */
		boolean canDowngradeTo(int access, int deny) {
			return (reachable & 1 << (access | deny<<2)) != 0;
		}

		/** After an OPEN_DOWNGRADE only the pairs within the new one are in effect. */
		void downgraded(int access, int deny) {
			int p = access | deny<<2;
			int r = 0;
			for (int q = 0; q < 16; q++) {
				if( (reachable & 1 << q) != 0 && (q & ~p) == 0 ) {
					r |= 1 << q;
				}
			}
			reachable = r | 1 << p;
		}

		public Node getNode() {
			return node;
		}

		public int getAccess() {
			return access;
		}

		public int getDeny() {
			return deny;
		}

		public OpenOwner getOwner() {
			return owner;
		}

		/** @return the stateid4 to send: seqid then other */
		public StateId stateId() {
			return new StateId(seqid, other);
		}
	}

	/** A stateid4. */
	public static final class StateId {
		public static final StateId ANONYMOUS = new StateId(0, new byte[Nfs4.NFS4_OTHER_SIZE]);
		public static final StateId BYPASS;
		static {
			byte[] ones = new byte[Nfs4.NFS4_OTHER_SIZE];
			Arrays.fill(ones, (byte) 0xff);
			BYPASS = new StateId(0xffffffff, ones);
		}
		public final int seqid;
		public final byte[] other;

		public StateId(int seqid, byte[] other) {
			this.seqid = seqid;
			this.other = other;
		}

		public boolean isAnonymous() {
			return seqid == 0 && Arrays.equals(other, ANONYMOUS.other);
		}

		public boolean isBypass() {
			return seqid == 0xffffffff && Arrays.equals(other, BYPASS.other);
		}

		public boolean isSpecial() {
			return isAnonymous() || isBypass();
		}
	}

	// ================================================================== client IDs

	/** NFS4ERR_CLID_INUSE: another principal holds the client's ID; its callback address goes in the reply. */
	public static class ClientInUseException extends Nfs4Exception {
		private static final long serialVersionUID = 1L;
		public final String netid;
		public final String addr;

		ClientInUseException(String netid, String addr) {
			super(Nfs4.NFS4ERR_CLID_INUSE);
			this.netid = netid;
			this.addr = addr;
		}
	}

	private static String nameKey(byte[] id) {
		return Base64.getEncoder().encodeToString(id);
	}

	private long newClientid() {
		return ((long) boot << 32) | (clientSeq.incrementAndGet() & 0xffffffffL);
	}

	private byte[] newVerifier() {
		byte[] ret = new byte[Nfs4.NFS4_VERIFIER_SIZE];
		random.nextBytes(ret);
		return ret;
	}

	/**
	 * SETCLIENTID (RFC 7530 section 16.33.5).
	 * @return the unconfirmed record: its client ID and the verifier SETCLIENTID_CONFIRM must give
	 */
	public synchronized ClientRecord setClientId(byte[] verifier, byte[] id, String principal, String cbNetid, String cbAddr,
			long cbProgram, int cbIdent, java.net.InetAddress from) throws Nfs4Exception {
		String name = nameKey(id);
		ClientRecord confirmed = confirmedByName.get(name);
		if( confirmed != null && !confirmed.principal.equals(principal) && hasState(confirmed) ) {
			throw new ClientInUseException(confirmed.cbNetid, confirmed.cbAddr);
		}
		ClientRecord old = unconfirmedByName.remove(name);
		if( old != null ) {
			unconfirmedById.remove(old.clientid);
		}
		long clientid;
		if( confirmed != null && Arrays.equals(confirmed.verifier, verifier) && confirmed.principal.equals(principal) ) {
			// a callback update: same client ID
			clientid = confirmed.clientid;
		} else {
			clientid = newClientid();
		}
		ClientRecord ret = new ClientRecord(clientid, verifier.clone(), name, principal, newVerifier(), cbNetid, cbAddr);
		ret.cbProgram = cbProgram;
		ret.cbIdent = cbIdent;
		ret.cbFrom = from;
		unconfirmedByName.put(name, ret);
		unconfirmedById.put(clientid, ret);
		return ret;
	}

	private boolean hasState(ClientRecord c) {
		return !c.owners.isEmpty() || !c.lockOwners.isEmpty();
	}

	/**
	 * SETCLIENTID_CONFIRM (RFC 7530 section 16.34).
	 */
	public synchronized void confirm(long clientid, byte[] confirmVerifier, String principal) throws Nfs4Exception {
		ClientRecord u = unconfirmedById.get(clientid);
		if( u != null && Arrays.equals(u.confirmVerifier, confirmVerifier) ) {
			if( !u.principal.equals(principal) ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_CLID_INUSE);
			}
			unconfirmedById.remove(clientid);
			unconfirmedByName.remove(u.name);
			ClientRecord c = confirmedByName.get(u.name);
			if( c != null && c.clientid == clientid ) {
				// a callback update: the record (and its state) stays, with the new callback
				c.cbNetid = u.cbNetid;
				c.cbAddr = u.cbAddr;
				c.cbProgram = u.cbProgram;
				c.cbIdent = u.cbIdent;
				c.cbFrom = u.cbFrom;
				c.cbUp = false;
				c.confirmVerifier = u.confirmVerifier;
				c.lastRenew = System.currentTimeMillis();
				return;
			}
			if( c != null ) {
				// the client rebooted: its old state goes, and its stateids are NFS4ERR_EXPIRED
				confirmedByName.remove(c.name);
				confirmedById.remove(c.clientid);
				dropState(c);
				rememberExpired(c);
			}
			u.confirmed = true;
			u.lastRenew = System.currentTimeMillis();
			confirmedById.put(clientid, u);
			confirmedByName.put(u.name, u);
			return;
		}
		ClientRecord c = confirmedById.get(clientid);
		if( c != null && Arrays.equals(c.confirmVerifier, confirmVerifier) ) {
			// a retransmission of a confirm that worked
			c.lastRenew = System.currentTimeMillis();
			return;
		}
		throw new Nfs4Exception(Nfs4.NFS4ERR_STALE_CLIENTID);
	}

	/**
	 * RENEW, and every use of a client ID.
	 * @return the confirmed client
	 * @throws Nfs4Exception NFS4ERR_STALE_CLIENTID (another run of the server, unknown or not
	 * confirmed) or NFS4ERR_EXPIRED (its lease ran out)
	 */
	public synchronized ClientRecord renew(long clientid) throws Nfs4Exception {
		if( (int) (clientid >>> 32) != boot ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_STALE_CLIENTID);
		}
		ClientRecord c = confirmedById.get(clientid);
		if( c == null ) {
			if( expired.contains((int) clientid) ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_EXPIRED);
			}
			throw new Nfs4Exception(Nfs4.NFS4ERR_STALE_CLIENTID);
		}
		c.lastRenew = System.currentTimeMillis();
		return c;
	}

	// ================================================================== open-owners and opens

	private static String ownerKey(byte[] owner) {
		return new String(owner, StandardCharsets.ISO_8859_1);
	}

	/**
	 * The open-owner of an OPEN, made if it is new (any seqid starts a new owner). An owner
	 * that was never confirmed is replaced (RFC 7530 section 16.16.5).
	 */
	public synchronized OpenOwner openOwner(ClientRecord client, byte[] owner, int seqid) {
		String key = ownerKey(owner);
		OpenOwner ret = client.owners.get(key);
		if( ret != null && !ret.confirmed && seqid != ret.seqid+1 && seqid != ret.seqid ) {
			dropOwner(ret);
			ret = null;
		}
		if( ret == null ) {
			ret = new OpenOwner(client, key, seqid);
			client.owners.put(key, ret);
		}
		return ret;
	}

	/**
	 * Open a file for an owner: a new open, or an upgrade of the owner's open of the same file.
	 * @throws Nfs4Exception NFS4ERR_SHARE_DENIED if an open conflicts, the owner's own open too
	 * (RFC 7530 sections 9.9 and 16.16.5)
	 */
	public synchronized OpenState open(OpenOwner owner, Node node, int access, int deny) throws Nfs4Exception {
		{
			OpenState mine = null;
			for (OpenState s : node.opens) {
				if( (access & s.deny) != 0 || (deny & s.access) != 0 ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_SHARE_DENIED);
				}
				if( s.owner == owner ) {
					mine = s;
				}
			}
			if( mine != null ) {
				mine.access |= access;
				mine.deny |= deny;
				mine.opened(access, deny);
				mine.seqid++;
				return mine;
			}
			OpenState ret = new OpenState(newOther(owner.client), owner, node, access, deny);
			node.opens.add(ret);
			owner.opens.add(ret);
			states.put(stateKey(ret.other), ret);
			return ret;
		}
	}

	private byte[] newOther(ClientRecord client) {
		ByteBuffer other = ByteBuffer.allocate(Nfs4.NFS4_OTHER_SIZE);
		other.putInt(boot);
		other.putInt((int) client.clientid);
		other.putInt(stateSeq.incrementAndGet());
		return other.array();
	}

	/** Confirm an owner whose first OPEN said OPEN4_RESULT_CONFIRM. */
	public synchronized void confirmOwner(OpenState state) {
		state.owner.confirmed = true;
		state.seqid++;
	}

	/**
	 * OPEN_DOWNGRADE: access and deny must be the union of what some of the open's OPENs asked
	 * for (RFC 7530 section 16.19.4).
	 */
	public synchronized void downgrade(OpenState state, int access, int deny) throws Nfs4Exception {
		{
			if( access < Nfs4.OPEN4_SHARE_ACCESS_READ || access > Nfs4.OPEN4_SHARE_ACCESS_BOTH
					|| deny < 0 || deny > Nfs4.OPEN4_SHARE_DENY_BOTH || !state.canDowngradeTo(access, deny) ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL);
			}
			state.access = access;
			state.deny = deny;
			state.downgraded(access, deny);
			state.seqid++;
		}
	}

	/**
	 * CLOSE: the lock states under the open go with it.
	 * @throws Nfs4Exception NFS4ERR_LOCKS_HELD if a lock-owner still holds locks under it (D20)
	 */
	public synchronized void close(OpenState state) throws Nfs4Exception {
		List<LockState> under = new ArrayList<LockState>();
		for (LockState l : state.node.locks) {
			if( l.open == state ) {
				if( !l.ranges.isEmpty() ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_LOCKS_HELD);
				}
				under.add(l);
			}
		}
		for (LockState l : under) {
			dropLockState(l);
		}
		state.node.opens.remove(state);
		state.closed = true;
		state.seqid++;
		state.owner.opens.remove(state);
		forgetClosed(state.owner);
		state.owner.lastClosed = state;
	}

	/**
	 * The owner's next seqid operation starts: an open its last operation closed is forgotten.
	 */
	public synchronized void forgetClosed(OpenOwner owner) {
		OpenState s = owner.lastClosed;
		if( s != null ) {
			owner.lastClosed = null;
			states.remove(stateKey(s.other));
		}
	}

	private static String stateKey(byte[] other) {
		return new String(other, StandardCharsets.ISO_8859_1);
	}

	/**
	 * Find the open a stateid names and check its seqid.
	 *
	 * @param id the stateid (not a special one)
	 * @param node the current file, or null not to check it
	 * @param renewLease true to renew the client's lease (every stateid use does)
	 * @throws Nfs4Exception NFS4ERR_STALE_STATEID, NFS4ERR_EXPIRED, NFS4ERR_BAD_STATEID or NFS4ERR_OLD_STATEID
	 */
	public OpenState find(StateId id, Node node, boolean renewLease) throws Nfs4Exception {
		return find(id, node, renewLease, false);
	}

	/**
	 * @param allowClosed true to find an open the owner's last operation closed (for a retransmitted CLOSE)
	 */
	public OpenState find(StateId id, Node node, boolean renewLease, boolean allowClosed) throws Nfs4Exception {
		if( id.other.length != Nfs4.NFS4_OTHER_SIZE ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID);
		}
		ByteBuffer b = ByteBuffer.wrap(id.other);
		int sboot = b.getInt();
		int client = b.getInt();
		if( sboot != boot ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_STALE_STATEID);
		}
		OpenState s;
		int current;
		synchronized (this) {
			s = states.get(stateKey(id.other));
			if( s == null || (s.closed && !allowClosed) ) {
				throw new Nfs4Exception(s == null && expired.contains(client) ? Nfs4.NFS4ERR_EXPIRED : Nfs4.NFS4ERR_BAD_STATEID);
			}
			if( renewLease ) {
				s.owner.client.lastRenew = System.currentTimeMillis();
			}
			current = s.seqid;
		}
		if( node != null && s.node != node ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID, "stateid of another file");
		}
		if( id.seqid - current > 0 ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID);
		}
		if( id.seqid != current ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_OLD_STATEID);
		}
		return s;
	}

	/**
	 * The open behind a stateid of a READ, WRITE or SETATTR: an open's stateid or a lock
	 * stateid (RFC 7530 section 9.1.4), its seqid checked.
	 */
	public OpenState findIo(StateId id, Node node) throws Nfs4Exception {
		LockState l;
		synchronized (this) {
			l = lockStates.get(stateKey(id.other));
		}
		if( l == null ) {
			return find(id, node, true);
		}
		return findLock(id, node, true).open;
	}

	/**
	 * Find the lock state a lock stateid names.
	 * @param checkSeqid false to skip the seqid check (a retransmission has an old one)
	 * @throws Nfs4Exception NFS4ERR_STALE_STATEID, NFS4ERR_EXPIRED, NFS4ERR_BAD_STATEID or NFS4ERR_OLD_STATEID
	 */
	public LockState findLock(StateId id, Node node, boolean checkSeqid) throws Nfs4Exception {
		if( id.other.length != Nfs4.NFS4_OTHER_SIZE || id.isSpecial() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID);
		}
		ByteBuffer b = ByteBuffer.wrap(id.other);
		int sboot = b.getInt();
		int client = b.getInt();
		if( sboot != boot ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_STALE_STATEID);
		}
		LockState l;
		int current;
		synchronized (this) {
			l = lockStates.get(stateKey(id.other));
			if( l == null || l.closed ) {
				throw new Nfs4Exception(l == null && expired.contains(client) ? Nfs4.NFS4ERR_EXPIRED : Nfs4.NFS4ERR_BAD_STATEID);
			}
			l.owner.client.lastRenew = System.currentTimeMillis();
			current = l.seqid;
		}
		if( node != null && l.node != node ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID, "stateid of another file");
		}
		if( checkSeqid ) {
			if( id.seqid - current > 0 ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID);
			}
			if( id.seqid != current ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_OLD_STATEID);
			}
		}
		return l;
	}

	// ================================================================== locks (D17)

	/**
	 * The lock-owner a LOCK with open_to_lock_owner4 names, made if it is new; an existing one
	 * takes the new lock_seqid (a client starts over after a LOCK that was denied).
	 */
	public synchronized LockOwner lockOwner(ClientRecord client, byte[] owner, int lockSeqid) {
		String key = ownerKey(owner);
		LockOwner ret = client.lockOwners.get(key);
		if( ret == null ) {
			ret = new LockOwner(client, key, lockSeqid);
			client.lockOwners.put(key, ret);
		} else {
			ret.seqid = lockSeqid-1;
			ret.lastResult = null;
		}
		return ret;
	}

	/** The owner's lock state on the open's file, made if it has none. */
	public synchronized LockState lockState(LockOwner owner, OpenState open) throws Nfs4Exception {
		LockState ret = owner.states.get(open.node);
		if( ret != null ) {
			if( ret.open != open ) {
				// one lock state per lock-owner and file
				throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID, "the lock-owner locks the file under another open");
			}
			return ret;
		}
		ret = new LockState(newOther(owner.client), owner, open, open.node);
		owner.states.put(open.node, ret);
		open.node.locks.add(ret);
		lockStates.put(stateKey(ret.other), ret);
		return ret;
	}

	/** Forget a lock state that never held a lock (its LOCK was denied: no stateid was given). */
	public synchronized void forgetIfUnused(LockState l) {
		if( l.ranges.isEmpty() && l.seqid == 0 ) {
			dropLockState(l);
		}
	}

	private void dropLockState(LockState l) {
		l.closed = true;
		l.ranges.clear();
		l.node.locks.remove(l);
		l.owner.states.remove(l.node);
		lockStates.remove(stateKey(l.other));
	}

	/**
	 * @return end of a range (exclusive), -1 for the largest offset
	 * @throws Nfs4Exception NFS4ERR_INVAL for a length of 0 or one past 2^64
	 */
	public static long end(long offset, long length) throws Nfs4Exception {
		if( length == -1L ) {
			return -1L;
		}
		long end = offset+length;
		if( length == 0 || Long.compareUnsigned(end, offset) <= 0 ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "lock range");
		}
		return end;
	}

	/** @return a lock of another owner than mine that conflicts, or null */
	private static LockDeniedException conflict(Node node, SeqOwner mine, boolean write, long start, long end) {
		for (LockState l : node.locks) {
			if( l.owner == mine ) {
				continue;
			}
			for (Range r : l.ranges) {
				if( (write || r.write) && r.overlaps(start, end) ) {
					return new LockDeniedException(r, l.owner);
				}
			}
		}
		return null;
	}

	/**
	 * LOCK: take, upgrade or downgrade the range (POSIX: the new type replaces the owner's own
	 * locks there).
	 * @throws LockDeniedException NFS4ERR_DENIED if another owner's lock conflicts
	 */
	public synchronized void lock(LockState l, boolean write, long start, long end) throws Nfs4Exception {
		if( l.closed ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID);
		}
		LockDeniedException denied = conflict(l.node, l.owner, write, start, end);
		if( denied != null ) {
			throw denied;
		}
		remove(l.ranges, start, end);
		l.ranges.add(new Range(start, end, write));
		merge(l.ranges);
		l.seqid++;
	}

	/** LOCKU: unlock the range (a part of a lock splits it). */
	public synchronized void unlock(LockState l, long start, long end) throws Nfs4Exception {
		if( l.closed ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID);
		}
		remove(l.ranges, start, end);
		l.seqid++;
	}

	/**
	 * LOCKT
	 * @return the conflicting lock, or null if the lock could be taken
	 */
	public synchronized LockDeniedException test(Node node, ClientRecord client, byte[] owner, boolean write, long start, long end) {
		LockOwner mine = client.lockOwners.get(ownerKey(owner));
		return conflict(node, mine, write, start, end);
	}

	/**
	 * RELEASE_LOCKOWNER
	 * @throws Nfs4Exception NFS4ERR_LOCKS_HELD if the owner still holds locks (D20)
	 */
	public synchronized void releaseLockOwner(ClientRecord client, byte[] owner) throws Nfs4Exception {
		LockOwner o = client.lockOwners.get(ownerKey(owner));
		if( o == null ) {
			return;
		}
		if( o.holdsLocks() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_LOCKS_HELD);
		}
		dropLockOwner(o);
	}

	private void dropLockOwner(LockOwner o) {
		for (LockState l : new ArrayList<LockState>(o.states.values())) {
			dropLockState(l);
		}
		o.client.lockOwners.remove(o.key);
	}

	/** Take [start, end) out of sorted, non-overlapping ranges, splitting one that spans it. */
	private static void remove(List<Range> ranges, long start, long end) {
		List<Range> ret = new ArrayList<Range>();
		for (Range r : ranges) {
			if( !r.overlaps(start, end) ) {
				ret.add(r);
				continue;
			}
			if( Long.compareUnsigned(r.start, start) < 0 ) {
				ret.add(new Range(r.start, start, r.write));
			}
			if( end != -1L && Long.compareUnsigned(end, r.end) < 0 ) {
				ret.add(new Range(end, r.end, r.write));
			}
		}
		ranges.clear();
		ranges.addAll(ret);
	}

	/** Sort, and join neighbors of the same type. */
	private static void merge(List<Range> ranges) {
		ranges.sort((a, b) -> Long.compareUnsigned(a.start, b.start));
		List<Range> ret = new ArrayList<Range>();
		for (Range r : ranges) {
			Range last = ret.isEmpty() ? null : ret.get(ret.size()-1);
			if( last != null && last.write == r.write && last.end != -1L && last.end == r.start ) {
				ret.set(ret.size()-1, new Range(last.start, r.end, r.write));
			} else {
				ret.add(r);
			}
		}
		ranges.clear();
		ranges.addAll(ret);
	}

	public synchronized int getLockCount() {
		int ret = 0;
		for (LockState l : lockStates.values()) {
			ret += l.ranges.size();
		}
		return ret;
	}

	/**
	 * Check an anonymous (special stateid) READ or WRITE against the file's share reservations.
	 * @throws Nfs4Exception NFS4ERR_LOCKED if an open denies it
	 */
	public synchronized void checkAnonymous(Node node, int access) throws Nfs4Exception {
		for (OpenState s : node.opens) {
			if( (access & s.deny) != 0 ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_LOCKED);
			}
		}
	}

	/** @return true if any open of the node denies the access (e.g. a REMOVE could check) */
	public synchronized boolean hasOpens(Node node) {
		return !node.opens.isEmpty();
	}

	/** @return true if an open of the node has write access (its write stream stays open, D16) */
	public synchronized boolean hasWriteOpen(Node node) {
		for (OpenState s : node.opens) {
			if( (s.access & Nfs4.OPEN4_SHARE_ACCESS_WRITE) != 0 ) {
				return true;
			}
		}
		return false;
	}

	// ================================================================== delegations

	/** A read delegation (RFC 7530 section 10.4): the client may serve reads of the file itself until it is recalled. */
	public static final class DelegationState {
		final byte[] other;
		final ClientRecord client;
		final Node node;
		/** when the recall began, 0 before; the client has the lease time from then to return it */
		volatile long recalledAt;
		/** set by whoever sends the CB_RECALL, so one is sent */
		final java.util.concurrent.atomic.AtomicBoolean recallSent = new java.util.concurrent.atomic.AtomicBoolean();

		DelegationState(byte[] other, ClientRecord client, Node node) {
			this.other = other;
			this.client = client;
			this.node = node;
		}

		public StateId stateId() {
			return new StateId(1, other);
		}

		public ClientRecord getClient() {
			return client;
		}

		public Node getNode() {
			return node;
		}
	}

	/**
	 * Hand a client a read delegation of a file it has just opened for reading, if that is safe: its callback
	 * path is up, nobody has the file open for writing, no delegation of it is being recalled and the client
	 * has none already.
	 * @return the delegation, null if none is given
	 */
	public synchronized DelegationState grantRead(ClientRecord c, Node node) {
		if( !c.cbUp || !c.confirmed || confirmedById.get(c.clientid) != c ) {
			return null;
		}
		for (DelegationState d : node.delegations) {
			if( d.client == c || d.recalledAt != 0 ) {
				return null;
			}
		}
		for (OpenState s : node.opens) {
			if( (s.access & Nfs4.OPEN4_SHARE_ACCESS_WRITE) != 0 ) {
				return null;
			}
		}
		DelegationState d = new DelegationState(newOther(c), c, node);
		node.delegations.add(d);
		c.delegations.add(d);
		delegationStates.put(stateKey(d.other), d);
		return d;
	}

	/**
	 * The delegations of a file that a conflicting access has to wait for. The recall of each begins (the
	 * caller sends the CB_RECALL); a delegation whose recall ran past the lease is revoked and left out.
	 * @param ignore a client whose own delegation is no conflict (it reopens by it), may be null
	 * @return the delegations to wait for, empty if the access may go on
	 */
	public synchronized List<DelegationState> recallFor(Node node, ClientRecord ignore, long now) {
		List<DelegationState> ret = new ArrayList<DelegationState>();
		for (DelegationState d : new ArrayList<DelegationState>(node.delegations)) {
			if( d.client == ignore ) {
				continue;
			}
			if( d.recalledAt != 0 && now - d.recalledAt > leaseMillis ) {
				revoke(d);
				continue;
			}
			if( d.recalledAt == 0 ) {
				d.recalledAt = now;
			}
			ret.add(d);
		}
		return ret;
	}

	/** Take back the delegations whose recall has run past the lease. */
	public synchronized void revokeOverdue(long now) {
		for (DelegationState d : new ArrayList<DelegationState>(delegationStates.values())) {
			if( d.recalledAt != 0 && now - d.recalledAt > leaseMillis ) {
				revoke(d);
			}
		}
	}

	private void revoke(DelegationState d) {
		forget(d);
		revokedDelegations.add(stateKey(d.other));
		if( revokedDelegations.size() > EXPIRED_REMEMBERED ) {
			Iterator<String> i = revokedDelegations.iterator();
			i.next();
			i.remove();
		}
	}

	private void forget(DelegationState d) {
		d.node.delegations.remove(d);
		d.client.delegations.remove(d);
		delegationStates.remove(stateKey(d.other));
	}

	/**
	 * The delegation a stateid names.
	 * @return null if the stateid isn't a delegation's
	 * @throws Nfs4Exception NFS4ERR_DELEG_REVOKED, or NFS4ERR_BAD_STATEID if it's another file's delegation
	 */
	public synchronized DelegationState findDelegation(StateId id, Node node) throws Nfs4Exception {
		if( id.isSpecial() || id.other.length != Nfs4.NFS4_OTHER_SIZE ) {
			return null;
		}
		String key = stateKey(id.other);
		DelegationState d = delegationStates.get(key);
		if( d == null ) {
			if( revokedDelegations.contains(key) ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_DELEG_REVOKED);
			}
			return null;
		}
		if( d.node != node ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID, "delegation of another file");
		}
		d.client.lastRenew = System.currentTimeMillis();
		return d;
	}

	/** DELEGRETURN */
	public synchronized void returnDelegation(StateId id, Node node) throws Nfs4Exception {
		DelegationState d = findDelegation(id, node);
		if( d == null ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID);
		}
		forget(d);
	}

	public synchronized int getDelegationCount() {
		return delegationStates.size();
	}

	// ================================================================== leases

	/**
	 * Expire the clients whose lease ran out: their opens are closed.
	 * @return the nodes that lost opens
	 */
	public synchronized List<Node> expire(long now) {
		List<Node> ret = new ArrayList<Node>();
		for (Iterator<ClientRecord> i = confirmedById.values().iterator(); i.hasNext();) {
			ClientRecord c = i.next();
			if( now - c.lastRenew > leaseMillis ) {
				i.remove();
				confirmedByName.remove(c.name);
				ret.addAll(dropState(c));
				rememberExpired(c);
			}
		}
		for (Iterator<ClientRecord> i = unconfirmedById.values().iterator(); i.hasNext();) {
			ClientRecord c = i.next();
			if( now - c.lastRenew > leaseMillis ) {
				i.remove();
				unconfirmedByName.remove(c.name);
			}
		}
		return ret;
	}

	private void rememberExpired(ClientRecord c) {
		expired.add((int) c.clientid);
		if( expired.size() > EXPIRED_REMEMBERED ) {
			Iterator<Integer> e = expired.iterator();
			e.next();
			e.remove();
		}
	}

	private List<Node> dropState(ClientRecord c) {
		List<Node> ret = new ArrayList<Node>();
		for (DelegationState d : new ArrayList<DelegationState>(c.delegations)) {
			forget(d);
		}
		// the client's locks go with it (D20)
		for (LockOwner o : new ArrayList<LockOwner>(c.lockOwners.values())) {
			dropLockOwner(o);
		}
		for (OpenOwner o : new ArrayList<OpenOwner>(c.owners.values())) {
			ret.addAll(dropOwner(o));
		}
		return ret;
	}

	private List<Node> dropOwner(OpenOwner o) {
		List<Node> ret = new ArrayList<Node>();
		for (OpenState s : new ArrayList<OpenState>(o.opens)) {
			for (LockState l : new ArrayList<LockState>(s.node.locks)) {
				if( l.open == s ) {
					dropLockState(l);
				}
			}
			s.node.opens.remove(s);
			s.closed = true;
			states.remove(stateKey(s.other));
			ret.add(s.node);
		}
		o.opens.clear();
		forgetClosed(o);
		o.client.owners.remove(o.key);
		return ret;
	}

	public synchronized int getClientCount() {
		return confirmedById.size();
	}

	public synchronized int getOpenCount() {
		return states.size();
	}

	/** For logging: the clients by ID. */
	public synchronized Map<String, String> describe() {
		Map<String, String> ret = new LinkedHashMap<String, String>();
		for (ClientRecord c : confirmedById.values()) {
			ret.put(c.toString(), c.owners.size()+" owners");
		}
		return ret;
	}
}
