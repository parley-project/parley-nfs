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

	private final UserRegistry registry;
	private final IdMap idMap;
	private final String anonymous;
	private final String serverName;
	private final long cacheMillis;
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
	 * @param idMap uid to user name
	 * @param anonymous the user name of unmapped uids and AUTH_NONE, null to refuse them
	 * @param serverName for the requester
	 * @param cacheMillis how long an answer is kept
	 */
	public Authorizer(UserRegistry registry, IdMap idMap, String anonymous, String serverName, long cacheMillis) {
		this.registry = registry;
		this.idMap = idMap;
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
		if( registry == null ) {
			return;
		}
		String permission = write ? StandardPermissions.WRITE : StandardPermissions.READ;
		String key = export.getName()+"|"+caller.getFlavor()+"|"+caller.getUid()+"|"+permission+"|"+client;
		long now = System.currentTimeMillis();
		Entry e = cache.get(key);
		if( e == null || e.until < now ) {
			AuthorizationResult r = decide(export, caller, client, localPort, permission);
			e = new Entry(r, now+cacheMillis);
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

	private AuthorizationResult decide(Export export, Caller caller, InetAddress client, int localPort, String permission) {
		String name = null;
		if( caller.getFlavor() == Rpc.AUTH_SYS ) {
			name = idMap == null ? null : idMap.userName(caller.getUid());
		}
		if( name == null ) {
			name = anonymous;
		}
		if( name == null ) {
			return AuthorizationResult.denied(caller+" isn't mapped to a user and there is no anonymous user");
		}
		Requester requester = Requester.connected(client, localPort, false, Requester.Transport.TCP, serverName);
		AuthenticationResult login;
		try {
			if( registry.supports(VouchedCredential.class, null) ) {
				login = registry.authenticate(new VouchedCredential(name, VOUCHED_BY, requester));
			} else {
				ParleyPrincipal user = registry.findUser(name);
				login = user == null ? AuthenticationResult.refused("no user "+name)
						: AuthenticationResult.authenticated(user, METHOD);
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
	}
}
