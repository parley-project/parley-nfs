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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import us.bringardner.parley.nfs.protocol.Nfs4;

/**
 * What RFC 7530 section 6 says about a list of {@link Ace}s, without knowing who anyone is:
 * checking a request against the list (6.2.1), the list a file has when it has none (made from
 * its mode), the list a new file or folder gets from its folder (6.4.3), and what a client may
 * send (decision D53).
 *
 * @author Tony Bringardner
 */
public final class Acls {

	/** The most entries one list may have. */
	public static final int MAX_ACES = 128;

	private static final int ALL_FLAGS = 0x7f;
	private static final int INHERIT_FLAGS = Nfs4.ACE4_FILE_INHERIT_ACE | Nfs4.ACE4_DIRECTORY_INHERIT_ACE
			| Nfs4.ACE4_NO_PROPAGATE_INHERIT_ACE | Nfs4.ACE4_INHERIT_ONLY_ACE;

	private Acls() {
	}

	/** Decides whether an entry is about the caller. */
	public interface Matcher {
		boolean applies(Ace ace);
	}

	/**
	 * RFC 7530 section 6.2.1: the entries are read in order; an allow entry grants the permissions
	 * it names, a deny entry refuses those not granted before it. Entries that only pass to new
	 * files (inherit only) are skipped.
	 *
	 * @param requested the permissions asked for
	 * @return true if every one is granted
	 */
	public static boolean allows(List<Ace> acl, int requested, Matcher who) {
		int remaining = requested;
		for (Ace a : acl) {
			if( remaining == 0 ) {
				break;
			}
			if( a.isInheritOnly() || !(a.isAllow() || a.isDeny()) || (a.getMask() & remaining) == 0 || !who.applies(a) ) {
				continue;
			}
			if( a.isDeny() ) {
				return false;
			}
			remaining &= ~a.getMask();
		}
		return remaining == 0;
	}

	/**
	 * The list a file shows when it has none: what its owner, its group and everyone may do, read from
	 * the mode bits.
	 */
	public static List<Ace> fromMode(int mode, boolean dir) {
		int base = Nfs4.ACE4_SYNCHRONIZE | Nfs4.ACE4_READ_ACL | Nfs4.ACE4_READ_ATTRIBUTES | Nfs4.ACE4_READ_NAMED_ATTRS;
		int ownerOnly = Nfs4.ACE4_WRITE_ACL | Nfs4.ACE4_WRITE_ATTRIBUTES | Nfs4.ACE4_WRITE_NAMED_ATTRS;
		List<Ace> ret = new ArrayList<Ace>();
		ret.add(Ace.allow(0, base | ownerOnly | bits((mode >> 6) & 7, dir), Ace.OWNER));
		ret.add(Ace.allow(0, base | bits((mode >> 3) & 7, dir), Ace.GROUP));
		ret.add(Ace.allow(0, base | bits(mode & 7, dir), Ace.EVERYONE));
		return ret;
	}

	private static int bits(int rwx, boolean dir) {
		int m = 0;
		if( (rwx & 4) != 0 ) {
			m |= Nfs4.ACE4_READ_DATA;
		}
		if( (rwx & 2) != 0 ) {
			m |= Nfs4.ACE4_WRITE_DATA | Nfs4.ACE4_APPEND_DATA;
			if( dir ) {
				m |= Nfs4.ACE4_DELETE_CHILD;
			}
		}
		if( (rwx & 1) != 0 ) {
			m |= Nfs4.ACE4_EXECUTE;
		}
		return m;
	}

	/**
	 * RFC 7530 section 6.4.3: the entries a new file or folder gets from the list of the folder it is made in.
	 *
	 * @param parent the folder's list
	 * @param childIsDir true for a new folder
	 * @return the child's list; empty when nothing passes down
	 */
	public static List<Ace> inherit(List<Ace> parent, boolean childIsDir) {
		List<Ace> ret = new ArrayList<Ace>();
		for (Ace a : parent) {
			int f = a.getFlags();
			boolean file = (f & Nfs4.ACE4_FILE_INHERIT_ACE) != 0;
			boolean folder = (f & Nfs4.ACE4_DIRECTORY_INHERIT_ACE) != 0;
			boolean stop = (f & Nfs4.ACE4_NO_PROPAGATE_INHERIT_ACE) != 0;
			if( !(a.isAllow() || a.isDeny()) ) {
				continue;
			}
			if( childIsDir ) {
				if( folder ) {
					int nf = f & ~Nfs4.ACE4_INHERIT_ONLY_ACE;
					if( stop ) {
						nf &= ~INHERIT_FLAGS;
					}
					ret.add(new Ace(a.getType(), nf, a.getMask(), a.getWho()));
				} else if( file && !stop ) {
					// the folder isn't affected itself but passes the entry on to its files
					ret.add(new Ace(a.getType(), f | Nfs4.ACE4_INHERIT_ONLY_ACE, a.getMask(), a.getWho()));
				}
			} else if( file ) {
				ret.add(new Ace(a.getType(), f & ~INHERIT_FLAGS, a.getMask(), a.getWho()));
			}
		}
		return ret;
	}

	/**
	 * Check a list a client sent (decision D53).
	 *
	 * @throws Nfs4Exception NFS4ERR_ATTRNOTSUPP for an audit or alarm entry, NFS4ERR_INVAL for an entry that
	 * makes no sense or too many entries
	 */
	public static List<Ace> validate(List<Ace> acl) throws Nfs4Exception {
		if( acl.size() > MAX_ACES ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "more than "+MAX_ACES+" entries");
		}
		for (Ace a : acl) {
			if( a.getType() == Nfs4.ACE4_SYSTEM_AUDIT_ACE_TYPE || a.getType() == Nfs4.ACE4_SYSTEM_ALARM_ACE_TYPE ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_ATTRNOTSUPP, "audit and alarm entries aren't supported");
			}
			if( !(a.isAllow() || a.isDeny()) ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "entry type "+a.getType());
			}
			if( (a.getFlags() & ~ALL_FLAGS) != 0 ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "entry flags 0x"+Integer.toHexString(a.getFlags()));
			}
			if( (a.getMask() & ~Nfs4.ACE4_ALL_MASK) != 0 ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "entry mask 0x"+Integer.toHexString(a.getMask()));
			}
			if( a.getWho().isEmpty() ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_BADOWNER, "an entry without who");
			}
			if( a.isSpecial() && a.isGroup() && !a.getWho().equals(Ace.GROUP) ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, a.getWho()+" with the group flag");
			}
		}
		return Collections.unmodifiableList(new ArrayList<Ace>(acl));
	}
}
