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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The nodes of one export, by fileid and by path.
 * <p>
 * A fileid is a 64 bit hash of the path the file had when the server first saw it, so the
 * same file gets the same fileid after a restart unless it was renamed; a rename keeps the
 * node (and so the fileid and the handle) while this server runs.
 *
 * @author Tony Bringardner
 */
public class NodeTable {

	private final Export owner;
	private final Map<Long, Node> byId = new HashMap<Long, Node>();
	private final Map<String, Node> byPath = new HashMap<String, Node>();

	/** Nodes that belong to no export (for tests of the table). */
	public NodeTable() {
		this(null);
	}

	/** @param owner the export these nodes are in */
	public NodeTable(Export owner) {
		this.owner = owner;
	}

	/**
	 * @return the node for a path, made if there isn't one
	 */
	public synchronized Node get(String path) {
		return get(path, hash(path));
	}

	/**
	 * @param preferredId the fileid to give a new node when it is free (one from a handle made
	 * before a restart)
	 * @return the node for a path, made if there isn't one
	 */
	public synchronized Node get(String path, long preferredId) {
		Node ret = byPath.get(path);
		if( ret == null ) {
			long id = preferredId;
			while( id == 0 || byId.containsKey(id) ) {
				id = mix(id+1);
			}
			ret = new Node(id, path, owner);
			byPath.put(path, ret);
			byId.put(id, ret);
		}
		return ret;
	}

	/** @return the node, null if this server hasn't seen it */
	public synchronized Node find(long fileid) {
		return byId.get(fileid);
	}

	/** @return the node, null if this server hasn't seen it */
	public synchronized Node findPath(String path) {
		return byPath.get(path);
	}

	/**
	 * A file or folder was renamed: it and everything under it keep their nodes at their new paths.
	 * A node already at the new path (a file that was replaced) is forgotten.
	 */
	public synchronized void renamed(String from, String to) {
		Node replaced = byPath.remove(to);
		if( replaced != null ) {
			byId.remove(replaced.getFileid());
		}
		String prefix = from+"/";
		List<Node> moved = new ArrayList<Node>();
		for (Map.Entry<String, Node> e : byPath.entrySet()) {
			if( e.getKey().equals(from) || e.getKey().startsWith(prefix) ) {
				moved.add(e.getValue());
			}
		}
		for (Node n : moved) {
			byPath.remove(n.getPath());
		}
		for (Node n : moved) {
			String p = n.getPath();
			String np = p.equals(from) ? to : to+p.substring(from.length());
			Node old = byPath.remove(np);
			if( old != null ) {
				byId.remove(old.getFileid());
			}
			n.setPath(np);
			byPath.put(np, n);
		}
	}

	/** A file or folder was removed: forget its node (handles to it become stale). */
	public synchronized void removed(String path) {
		Node n = byPath.remove(path);
		if( n != null ) {
			byId.remove(n.getFileid());
		}
	}

	public synchronized int size() {
		return byId.size();
	}

	/** FNV-1a over the path's UTF-8 bytes, then mixed. */
	static long hash(String path) {
		long h = 0xcbf29ce484222325L;
		for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
			h ^= b & 0xff;
			h *= 0x100000001b3L;
		}
		return mix(h);
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
		z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
		return z ^ (z >>> 33);
	}
}
