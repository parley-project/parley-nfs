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

import java.util.Objects;

import us.bringardner.parley.nfs.protocol.Nfs4;

/**
 * One access control entry, nfsace4 of RFC 7530 section 6.2: a type (allow or deny), flags, a
 * mask of permissions and who it is for. Immutable.
 *
 * @author Tony Bringardner
 */
public final class Ace {

	public static final String OWNER = "OWNER@";
	public static final String GROUP = "GROUP@";
	public static final String EVERYONE = "EVERYONE@";

	private final int type;
	private final int flags;
	private final int mask;
	private final String who;

	public Ace(int type, int flags, int mask, String who) {
		this.type = type;
		this.flags = flags;
		this.mask = mask;
		this.who = Objects.requireNonNull(who, "who");
	}

	public static Ace allow(int flags, int mask, String who) {
		return new Ace(Nfs4.ACE4_ACCESS_ALLOWED_ACE_TYPE, flags, mask, who);
	}

	public static Ace deny(int flags, int mask, String who) {
		return new Ace(Nfs4.ACE4_ACCESS_DENIED_ACE_TYPE, flags, mask, who);
	}

	public int getType() {
		return type;
	}

	public int getFlags() {
		return flags;
	}

	public int getMask() {
		return mask;
	}

	public String getWho() {
		return who;
	}

	public boolean isAllow() {
		return type == Nfs4.ACE4_ACCESS_ALLOWED_ACE_TYPE;
	}

	public boolean isDeny() {
		return type == Nfs4.ACE4_ACCESS_DENIED_ACE_TYPE;
	}

	public boolean isGroup() {
		return (flags & Nfs4.ACE4_IDENTIFIER_GROUP) != 0;
	}

	/** @return true for an entry that only passes to new files and folders, not applying to this one */
	public boolean isInheritOnly() {
		return (flags & Nfs4.ACE4_INHERIT_ONLY_ACE) != 0;
	}

	public boolean isSpecial() {
		return who.equals(OWNER) || who.equals(GROUP) || who.equals(EVERYONE);
	}

	@Override
	public boolean equals(Object o) {
		if( this == o ) {
			return true;
		}
		if( !(o instanceof Ace) ) {
			return false;
		}
		Ace a = (Ace) o;
		return type == a.type && flags == a.flags && mask == a.mask && who.equals(a.who);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, flags, mask, who);
	}

	@Override
	public String toString() {
		return (type == 0 ? "allow" : type == 1 ? "deny" : "type"+type)+" "+who+" mask=0x"+Integer.toHexString(mask)
				+" flags=0x"+Integer.toHexString(flags);
	}
}
