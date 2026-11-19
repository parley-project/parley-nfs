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

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.iam.UserRegistryUnavailableException;
import us.bringardner.parley.nfs.protocol.Nfs4;

/**
 * Checks a caller against a file's access control list (decisions D51 to D54). The registry's
 * read or write on the export is checked first by the {@link Authorizer}; this is the second check,
 * made only for a file that has a list. Who an entry is for is found like this: OWNER@ is the file's
 * owner, GROUP@ its group, EVERYONE@ everybody; a name is the registry user (or, with the group flag,
 * group) of that name, with or without this server's domain; a number is the AUTH_SYS uid (or gid) or
 * the registry user (group) whose {@code uid} ({@code gid}) attribute it is.
 * <p>
 * A file's owner always keeps READ_ACL and WRITE_ACL, so a list can't lock its owner out of it.
 *
 * @author Tony Bringardner
 */
public class AclPolicy {

	/** The permissions the owner of a file always has. */
	private static final int OWNER_ALWAYS = Nfs4.ACE4_READ_ACL | Nfs4.ACE4_WRITE_ACL;

	private static final class Names {
		final Set<String> names;
		final long until;

		Names(Set<String> names, long until) {
			this.names = names;
			this.until = until;
		}
	}

	private final Authorizer authorizer;
	private final Attributes attributes;
	private final UserRegistry registry;
	private final long cacheMillis;
	private final Map<String, Names> numbers = new ConcurrentHashMap<String, Names>();
	private volatile boolean enforceModes;

	/**
	 * @param registry the user registry, null for none (numbers and names are then compared as given)
	 * @param cacheMillis how long what the registry said about a number is kept
	 */
	public AclPolicy(Authorizer authorizer, Attributes attributes, UserRegistry registry, long cacheMillis) {
		this.authorizer = authorizer;
		this.attributes = attributes;
		this.registry = registry;
		this.cacheMillis = cacheMillis;
	}

	/**
	 * @param enforce true to check the mode bits of a file that has no list of its own (decision D73): the
	 * mode is turned into a list and the caller is checked against it by name, like any other list
	 */
	public void setEnforceModes(boolean enforce) {
		this.enforceModes = enforce;
	}

	public boolean isEnforceModes() {
		return enforceModes;
	}

	/** @return true if this export checks anything beyond the registry: it has lists or the modes are enforced */
	public boolean active(Export export) {
		return enforceModes || export.getAcls() != null;
	}

	/** @return the file's list, null when it has none or ACLs are off */
	public List<Ace> aclOf(Export export, Node node) {
		AclStore store = export.getAcls();
		return store == null ? null : store.get(node.getFileid());
	}

	/**
	 * @return the list that decides for this file: its own if it has one, else (when the modes are enforced)
	 * the one its mode bits make, else null for no check
	 */
	public List<Ace> aclOf(Export export, Node node, FileSource file) {
		List<Ace> acl = aclOf(export, node);
		if( acl != null || !enforceModes || file == null ) {
			return acl;
		}
		try {
			boolean dir = !Attributes.isLink(file) && file.isDirectory();
			return Acls.fromMode(Attributes.mode(new Attributes.Source(export, node, file, null)), dir);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * @return true if the caller holds these permissions on the file; true too for a file with no list
	 * @throws Nfs4Exception NFS4ERR_DELAY if the registry is unavailable
	 */
	public boolean permits(Export export, Node node, FileSource file, Caller caller, InetAddress client, int mask)
			throws Nfs4Exception {
		List<Ace> acl = aclOf(export, node, file);
		if( acl == null ) {
			return true;
		}
		return permits(acl, file, caller, client, mask);
	}

	/** @return true if the caller holds these permissions under this list */
	public boolean permits(List<Ace> acl, FileSource file, Caller caller, InetAddress client, int mask) throws Nfs4Exception {
		Authorizer.Identity who = authorizer.identity(caller, client);
		if( (mask & ~OWNER_ALWAYS) == 0 && isOwner(file, who) ) {
			return true;
		}
		return Acls.allows(acl, mask, ace -> applies(ace, file, who));
	}

	/** @throws Nfs4Exception NFS4ERR_ACCESS if the file has a list and it doesn't give the caller these permissions */
	public void check(Export export, Node node, FileSource file, Caller caller, InetAddress client, int mask)
			throws Nfs4Exception {
		if( !permits(export, node, file, caller, client, mask) ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_ACCESS, "the access control list of "+node+" refuses "+caller);
		}
	}

	private boolean isOwner(FileSource file, Authorizer.Identity who) {
		if( who.getName() == null ) {
			return false;
		}
		try {
			UserPrincipal p = file.getOwner();
			return p != null && same(p.getName(), who.getName());
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private boolean applies(Ace ace, FileSource file, Authorizer.Identity who) {
		String w = ace.getWho();
		if( w.equals(Ace.EVERYONE) ) {
			return true;
		}
		if( w.equals(Ace.OWNER) ) {
			return isOwner(file, who);
		}
		if( w.equals(Ace.GROUP) ) {
			try {
				GroupPrincipal g = file.getGroup();
				return g != null && inGroup(g.getName(), who);
			} catch (IOException | RuntimeException e) {
				return false;
			}
		}
		boolean number = w.chars().allMatch(Character::isDigit);
		if( ace.isGroup() ) {
			if( number ) {
				return hasGid(Long.parseLong(w), who);
			}
			return inGroup(w, who);
		}
		if( number ) {
			return hasUid(Long.parseLong(w), who);
		}
		return who.getName() != null && same(w, who.getName());
	}

	private boolean inGroup(String group, Authorizer.Identity who) {
		for (String g : who.getGroups()) {
			if( same(g, group) ) {
				return true;
			}
		}
		return false;
	}

	private boolean hasUid(long uid, Authorizer.Identity who) {
		if( uid == who.getUid() ) {
			return true;
		}
		if( who.getName() == null ) {
			return false;
		}
		for (String n : registryNames("uid", uid, false)) {
			if( same(n, who.getName()) ) {
				return true;
			}
		}
		return false;
	}

	private boolean hasGid(long gid, Authorizer.Identity who) {
		if( who.getGids().contains(gid) ) {
			return true;
		}
		for (String n : registryNames("gid", gid, true)) {
			if( inGroup(n, who) ) {
				return true;
			}
		}
		return false;
	}

	/** The names of the registry's users (or groups) that have this uid (gid), kept for a short time. */
	private Set<String> registryNames(String attribute, long number, boolean groups) {
		Set<String> none = new HashSet<String>();
		if( registry == null ) {
			return none;
		}
		String key = attribute+"|"+number+"|"+groups;
		long now = System.currentTimeMillis();
		Names n = numbers.get(key);
		if( n != null && n.until >= now ) {
			return n.names;
		}
		try {
			Set<String> names = new HashSet<String>();
			for (ParleyPrincipal p : registry.findByAttribute(attribute, number)) {
				if( p.isGroup() == groups ) {
					names.add(p.getName());
				}
			}
			if( numbers.size() > 10000 ) {
				numbers.values().removeIf(x -> x.until < now);
			}
			numbers.put(key, new Names(names, now+cacheMillis));
			return names;
		} catch (UserRegistryUnavailableException | RuntimeException e) {
			return none;
		}
	}

	/** Names are the same ignoring case and this server's domain. */
	boolean same(String a, String b) {
		return normal(a).equals(normal(b));
	}

	private String normal(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		String suffix = "@"+attributes.getDomain().toLowerCase(Locale.ROOT);
		return lower.endsWith(suffix) ? lower.substring(0, lower.length()-suffix.length()) : lower;
	}

	/** Forget what the registry said about numbers. */
	public void clear() {
		numbers.clear();
	}
}
