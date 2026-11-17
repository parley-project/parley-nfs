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
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.AuthorizationResult;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.iam.UserRegistryUnavailableException;
import us.bringardner.parley.iam.credential.VouchedCredential;
import us.bringardner.parley.iam.permission.BasicParleyPermission;
import us.bringardner.parley.iam.permission.StandardPermissions;
import us.bringardner.parley.nfs.rpc.Rpc;

/**
 * Decides whether a caller may read or change an export (decision D5).
 * <p>
 * With a user registry: the AUTH_SYS uid is mapped to a user name ({@link IdMap}; an unmapped
 * uid or AUTH_NONE is the anonymous user, if there is one), the user is found in the
 * registry, and the registry is asked for the {@code read} or {@code write} permission on the
 * export. AUTH_SYS proves nothing, so the server vouches for the identity: a registry that
 * accepts a {@link VouchedCredential} authenticates it (and may refuse to trust this server),
 * otherwise the user only has to exist. Answers are kept for a short time, so each request
 * doesn't ask the registry again.
 * <p>
 * Without a registry nobody is checked: the export's read only setting is the only control.
 *
 * @author Tony Bringardner
 */
public class Authorizer {

	/** How the server vouches for AUTH_SYS identities. */
	public static final String VOUCHED_BY = "nfs-auth_sys";
	public static final String METHOD = "auth_sys";
	/** How the server vouches for a Kerberos principal's user (decision D36). */
	public static final String VOUCHED_BY_GSS = "nfs-rpcsec_gss";
	public static final String METHOD_GSS = "rpcsec_gss";
	/** The principal attribute that names a user's root folder (decision D49). */
	public static final String ROOT_ATTRIBUTE = "nfs.root";
	/** A root attribute that isn't a path inside the export. */
	public static final String INVALID_ROOT = "//invalid";

	private final UserRegistry registry;
	private final IdNumbers numbers;
	private final String anonymous;
	private final String serverName;
	private final long cacheMillis;
	private final java.util.function.Consumer<String> refusals;
	private final Map<String, Entry> cache = new ConcurrentHashMap<String, Entry>();

	private static final class Entry {
		final AuthorizationResult result;
		final long until;

		Entry(AuthorizationResult result, long until) {
			this.result = result;
			this.until = until;
		}
	}

	/**
	 * @param registry the user registry, null for no checks
	 * @param numbers uid to user (the registry's uid attributes, then the id map); null for none
	 * @param anonymous the user name of unmapped uids and AUTH_NONE, null to refuse them
	 * @param serverName for the requester
	 * @param cacheMillis how long an answer is kept
	 */
	public Authorizer(UserRegistry registry, IdNumbers numbers, String anonymous, String serverName, long cacheMillis) {
		this(registry, numbers, anonymous, serverName, cacheMillis, null);
	}

	/**
	 * @param refusals told (once for each fresh answer, not each call) when a call is refused, so an
	 * operator can see why without turning on debug logging; null for none
	 */
	public Authorizer(UserRegistry registry, IdNumbers numbers, String anonymous, String serverName, long cacheMillis,
			java.util.function.Consumer<String> refusals) {
		this.refusals = refusals;
		this.registry = registry;
		this.numbers = numbers;
		this.anonymous = anonymous;
		this.serverName = serverName;
		this.cacheMillis = cacheMillis;
	}

	public boolean isChecking() {
		return registry != null;
	}

	/**
	 * @param write true for the write permission, false for read
	 * @throws Nfs4Exception NFS4ERR_ACCESS if not allowed, NFS4ERR_DELAY if the registry is unavailable
	 */
	public void check(Export export, Caller caller, InetAddress client, int localPort, boolean write) throws Nfs4Exception {
		if( registry == null || export.isPseudo() ) {
			// nothing to check, or the folder of exports, which holds no files (decision D29)
			return;
		}
		String permission = write ? StandardPermissions.WRITE : StandardPermissions.READ;
		String key = export.getName()+"|"+caller.getStateKey()+"|"+permission+"|"+client;
		long now = System.currentTimeMillis();
		Entry e = cache.get(key);
		if( e == null || e.until < now ) {
			AuthorizationResult r = decide(export, caller, client, localPort, permission);
			e = new Entry(r, now+cacheMillis);
			if( refusals != null && !r.isAllowed() ) {
				refusals.accept("Refused "+permission+" on "+export.getName()+" to "+caller+" from "+client+": "+r.getReason());
			}
			if( r.getStatus() != AuthorizationResult.Status.UNAVAILABLE ) {
				if( cache.size() > 10000 ) {
					prune(now);
				}
				cache.put(key, e);
			}
		}
		if( e.result.getStatus() == AuthorizationResult.Status.UNAVAILABLE ) {
			throw new Nfs4Exception(us.bringardner.parley.nfs.protocol.Nfs4.NFS4ERR_DELAY, e.result.getReason());
		}
		if( !e.result.isAllowed() ) {
			throw new Nfs4Exception(us.bringardner.parley.nfs.protocol.Nfs4.NFS4ERR_ACCESS, e.result.getReason());
		}
	}

	private void prune(long now) {
		for (Iterator<Entry> i = cache.values().iterator(); i.hasNext();) {
			if( i.next().until < now ) {
				i.remove();
			}
		}
		if( cache.size() > 10000 ) {
			cache.clear();
		}
	}

	/** The registry user a caller is: the Kerberos user, the id map's user for the uid, else the anonymous user; null for none. */
	private String userName(Caller caller) throws UserRegistryUnavailableException {
		boolean gss = caller.getFlavor() == Rpc.RPCSEC_GSS;
		String name = null;
		if( gss ) {
			name = caller.getUser();
		} else if( caller.getFlavor() == Rpc.AUTH_SYS ) {
			name = numbers == null ? null : numbers.user(caller.getUid());
		}
		if( name == null && !gss ) {
			name = anonymous;
		}
		return name;
	}

	/** Who a caller is for an access control list (decision D52): the user and the groups it is in. */
	public static final class Identity {
		private final String name;
		private final long uid;
		private final java.util.Set<Long> gids;
		private final java.util.Set<String> groups;
		private final String root;

		Identity(String name, long uid, java.util.Set<Long> gids, java.util.Set<String> groups, String root) {
			this.root = root;
			this.name = name;
			this.uid = uid;
			this.gids = gids;
			this.groups = groups;
		}

		/** @return the registry user's name, null when the caller isn't a user */
		public String getName() {
			return name;
		}

		/** @return the AUTH_SYS uid, -1 when there is none */
		public long getUid() {
			return uid;
		}

		/** @return the AUTH_SYS gid and gids */
		public java.util.Set<Long> getGids() {
			return gids;
		}

		/**
		 * @return the folder the user is confined to, as a path inside an export without a leading or trailing
		 * slash (decision D49); null for the whole export; {@link Authorizer#INVALID_ROOT} when the attribute
		 * can't be a folder inside the export, which leaves the user nothing
		 */
		public String getRoot() {
			return root;
		}

		/** @return the names of the groups the user is in, as the registry names them */
		public java.util.Set<String> getGroups() {
			return groups;
		}
	}

	private static final class IdEntry {
		final Identity identity;
		final long until;

		IdEntry(Identity identity, long until) {
			this.identity = identity;
			this.until = until;
		}
	}

	private final Map<String, IdEntry> identities = new ConcurrentHashMap<String, IdEntry>();

	/**
	 * Who the caller is, for checking an access control list. Without a registry the user is the
	 * id map's, and there are no groups.
	 *
	 * @throws Nfs4Exception NFS4ERR_DELAY if the registry is unavailable
	 */
	public Identity identity(Caller caller, InetAddress client) throws Nfs4Exception {
		String key = caller.getStateKey()+"|"+client;
		long now = System.currentTimeMillis();
		IdEntry e = identities.get(key);
		if( e != null && e.until >= now ) {
			return e.identity;
		}
		String name;
		try {
			name = userName(caller);
		} catch (UserRegistryUnavailableException ex) {
			throw new Nfs4Exception(us.bringardner.parley.nfs.protocol.Nfs4.NFS4ERR_DELAY, ex.getMessage());
		}
		java.util.Set<Long> gids = new java.util.HashSet<Long>();
		if( caller.getAuthSys() != null ) {
			gids.add(caller.getAuthSys().getGid());
			for (long g : caller.getAuthSys().getGids()) {
				gids.add(g);
			}
		}
		java.util.Set<String> groups = new java.util.HashSet<String>();
		String root = null;
		if( registry != null && name != null ) {
			try {
				ParleyPrincipal user = registry.findUser(name);
				if( user != null ) {
					name = user.getName();
					root = normalizeRoot(user.getAttribute(ROOT_ATTRIBUTE));
					for (ParleyPrincipal g : registry.findGroups(user)) {
						groups.add(g.getName());
					}
				}
			} catch (UserRegistryUnavailableException ex) {
				throw new Nfs4Exception(us.bringardner.parley.nfs.protocol.Nfs4.NFS4ERR_DELAY, ex.getMessage());
			}
		}
		Identity id = new Identity(name, caller.getUid(), gids, groups, root);
		if( identities.size() > 10000 ) {
			identities.values().removeIf(x -> x.until < now);
		}
		identities.put(key, new IdEntry(id, now+cacheMillis));
		return id;
	}

	/**
	 * @return the path inside the export a root attribute names, without leading or trailing slashes; null when
	 * there is none or it is the whole export; {@link #INVALID_ROOT} when it has a . or .. part
	 */
	static String normalizeRoot(Object attribute) {
		if( attribute == null ) {
			return null;
		}
		String text = attribute.toString().trim();
		StringBuilder out = new StringBuilder();
		for (String part : text.split("/")) {
			if( part.isEmpty() ) {
				continue;
			}
			if( part.equals(".") || part.equals("..") || part.indexOf('\0') >= 0 ) {
				return INVALID_ROOT;
			}
			if( out.length() > 0 ) {
				out.append('/');
			}
			out.append(part);
		}
		return out.length() == 0 ? null : out.toString();
	}

	private AuthorizationResult decide(Export export, Caller caller, InetAddress client, int localPort, String permission) {
		boolean gss = caller.getFlavor() == Rpc.RPCSEC_GSS;
		String name;
		try {
			name = userName(caller);
		} catch (UserRegistryUnavailableException ex) {
			return AuthorizationResult.unavailable(ex.getMessage());
		}
		if( name == null ) {
			return AuthorizationResult.denied(caller+" isn't mapped to a user and there is no anonymous user");
		}
		Requester requester = Requester.connected(client, localPort, false, Requester.Transport.TCP, serverName);
		AuthenticationResult login;
		try {
			if( registry.supports(VouchedCredential.class, null) ) {
				login = registry.authenticate(new VouchedCredential(name, gss ? VOUCHED_BY_GSS : VOUCHED_BY, requester));
			} else {
				ParleyPrincipal user = registry.findUser(name);
				login = user == null ? AuthenticationResult.refused("no user "+name)
						: AuthenticationResult.authenticated(user, gss ? METHOD_GSS : METHOD);
			}
		} catch (UserRegistryUnavailableException ex) {
			return AuthorizationResult.unavailable(ex.getMessage());
		}
		if( login.getStatus() == AuthenticationResult.Status.UNAVAILABLE ) {
			return AuthorizationResult.unavailable(login.getReason());
		}
		if( login.getStatus() != AuthenticationResult.Status.AUTHENTICATED ) {
			return AuthorizationResult.denied(login.getReason());
		}
		return registry.checkPermission(requester.loggedIn(login),
				new BasicParleyPermission(permission, export.getId()), export);
	}

	/** Forget the answers kept (e.g. after the registry or id map changed). */
	public void clear() {
		cache.clear();
		identities.clear();
		if( numbers != null ) {
			numbers.clear();
		}
	}
}
