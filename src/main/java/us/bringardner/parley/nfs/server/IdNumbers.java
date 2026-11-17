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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.iam.UserRegistryUnavailableException;

/**
 * The numbers NFS shows for users and groups, and the users behind the numbers it receives (decision D48).
 * <p>
 * A principal in the user registry carries them as attributes: {@value #UID} on a user and {@value #GID} on a
 * group. An AUTH_SYS uid is the user whose {@value #UID} attribute has that value (the registry's
 * {@code findByAttribute}); a file owner is sent as its user's {@value #UID}, a file group as its group's
 * {@value #GID}. The id map ({@link IdMap}) is the fallback for a user the registry doesn't give a number.
 * Where two users have the same uid the registry gives no answer, since the call can't tell who it is, and the
 * id map decides. Answers are kept for a short time.
 *
 * @author Tony Bringardner
 */
public class IdNumbers {

	public static final String UID = "uid";
	public static final String GID = "gid";

	private static final Object NONE = new Object();

	private final UserRegistry registry;
	private final IdMap idMap;
	private final long cacheMillis;
	private final Map<String, Cached> cache = new ConcurrentHashMap<String, Cached>();

	private static final class Cached {
		final Object value;
		final long until;

		Cached(Object value, long until) {
			this.value = value;
			this.until = until;
		}
	}

	/**
	 * @param registry the user registry, null for none
	 * @param idMap the fallback, null for none
	 * @param cacheMillis how long an answer is kept
	 */
	public IdNumbers(UserRegistry registry, IdMap idMap, long cacheMillis) {
		this.registry = registry;
		this.idMap = idMap;
		this.cacheMillis = cacheMillis;
	}

	/** @return true if there is a registry or an id map, so some number may be known */
	public boolean isEmpty() {
		return registry == null && (idMap == null || idMap.size() == 0);
	}

	/** @return the uid of a user: its {@value #UID} attribute, else the id map's, null for none */
	public Long uid(String userName) {
		Object c = cached("u|"+userName);
		if( c == null ) {
			Long ret = null;
			Long fromRegistry = number(userName, UID, false);
			if( fromRegistry != null ) {
				ret = fromRegistry;
			} else if( idMap != null ) {
				ret = idMap.uid(userName);
			}
			c = keep("u|"+userName, ret);
		}
		return c == NONE ? null : (Long) c;
	}

	/** @return the gid of a group: its {@value #GID} attribute, null for none */
	public Long gid(String groupName) {
		Object c = cached("g|"+groupName);
		if( c == null ) {
			c = keep("g|"+groupName, number(groupName, GID, true));
		}
		return c == NONE ? null : (Long) c;
	}

	/**
	 * @return the name of the user with this uid: the one user whose {@value #UID} attribute it is, else the id
	 * map's, null for none
	 * @throws UserRegistryUnavailableException if the registry can't answer now (nothing is kept)
	 */
	public String user(long uid) throws UserRegistryUnavailableException {
		Object c = cached("n|"+uid);
		if( c == null ) {
			String ret = null;
			if( registry != null ) {
				ParleyPrincipal found = null;
				int count = 0;
				Set<ParleyPrincipal> all = registry.findByAttribute(UID, Long.toString(uid));
				for (ParleyPrincipal p : all) {
					if( !p.isGroup() ) {
						found = p;
						count++;
					}
				}
				if( count == 1 ) {
					ret = found.getName();
				}
			}
			if( ret == null && idMap != null ) {
				ret = idMap.userName(uid);
			}
			c = keep("n|"+uid, ret);
		}
		return c == NONE ? null : (String) c;
	}

	/** The attribute of a principal as a number, null when it has none (or the registry can't say now). */
	private Long number(String name, String attribute, boolean group) {
		if( registry == null || name == null ) {
			return null;
		}
		try {
			ParleyPrincipal p = registry.findPrincipal(name);
			if( p == null || p.isGroup() != group ) {
				return null;
			}
			Object v = p.getAttribute(attribute);
			if( v == null ) {
				return null;
			}
			if( v instanceof Number ) {
				return ((Number) v).longValue();
			}
			return Long.parseLong(v.toString().trim());
		} catch (UserRegistryUnavailableException | NumberFormatException e) {
			return null;
		}
	}

	private Object cached(String key) {
		Cached c = cache.get(key);
		return c != null && c.until >= System.currentTimeMillis() ? c.value : null;
	}

	private Object keep(String key, Object value) {
		Object v = value == null ? NONE : value;
		if( cache.size() > 10000 ) {
			long now = System.currentTimeMillis();
			cache.values().removeIf(x -> x.until < now);
			if( cache.size() > 10000 ) {
				cache.clear();
			}
		}
		cache.put(key, new Cached(v, System.currentTimeMillis()+cacheMillis));
		return v;
	}

	/** Forget the answers kept (the registry or id map changed). */
	public void clear() {
		cache.clear();
	}
}
