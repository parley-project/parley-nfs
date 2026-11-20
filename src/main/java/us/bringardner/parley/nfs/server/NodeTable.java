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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The nodes of one export, by fileid and by path.
 * <p>
 * A fileid is a 64 bit hash of the path the file had when the server first saw it, so the
 * same file gets the same fileid after a restart unless it was renamed; a rename keeps the
 * node (and so the fileid and the handle) while this server runs.
 * <p>
 * With a {@link NodeStore} the fileids that can't be worked out from a path again are kept in a
 * file: those of renamed files, of ones that got another id because theirs was taken, and of
 * files whose path is too long for a handle. They survive a restart, so a handle still finds
 * its file (decision D41). Nodes nobody has asked for lately are dropped from memory when there
 * are more than {@link #getMaxNodes()} of them, and made again from the path or the stored
 * entry when asked for (decision D42).
 *
 * @author Tony Bringardner
 */
public class NodeTable {

	private final Export owner;
	private final Map<Long, Node> byId = new HashMap<Long, Node>();
	private final Map<String, Node> byPath = new HashMap<String, Node>();
	/** the identities that are kept in the file: fileid to path (all of them, active or not) */
	private final Map<Long, String> stored = new LinkedHashMap<Long, String>();
	private final Map<String, Long> storedByPath = new HashMap<String, Long>();
	private NodeStore store;
	private int maxNodes = DEFAULT_MAX_NODES;
	private long nextTrim;

	public static final int DEFAULT_MAX_NODES = 100_000;
	/** A node asked for this recently is never dropped: a call may be holding it. */
	static final long IDLE_BEFORE_DROP = 120_000;

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
		if( ret != null ) {
			ret.touch();
			return ret;
		}
		Long kept = storedByPath.get(path);
		if( kept != null ) {
			// a node dropped from memory, or one from before a restart: same fileid
			ret = new Node(kept, path, owner);
		} else {
			long id = preferredId;
			while( id == 0 || byId.containsKey(id) || stored.containsKey(id) ) {
				id = mix(id+1);
			}
			ret = new Node(id, path, owner);
			if( needsStore(id, path) ) {
				stored.put(id, path);
				storedByPath.put(path, id);
				if( store != null ) {
					store.add(id, path);
				}
			}
		}
		byPath.put(path, ret);
		byId.put(ret.getFileid(), ret);
		trim(false);
		return ret;
	}

	/** @return the node, null if this server hasn't got it in memory (see {@link #storedPath}) */
	public synchronized Node find(long fileid) {
		Node ret = byId.get(fileid);
		if( ret != null ) {
			ret.touch();
		}
		return ret;
	}

	/** @return the path a fileid had when it was stored, null if it isn't (decision D41) */
	public synchronized String storedPath(long fileid) {
		return stored.get(fileid);
	}

	/** The file of a stored fileid is gone: forget it. */
	public synchronized void forgetStored(long fileid) {
		String path = stored.remove(fileid);
		if( path != null ) {
			storedByPath.remove(path);
			if( store != null ) {
				store.removed(path);
			}
		}
	}

	/** @return the node, null if this server hasn't seen it */
	public synchronized Node findPath(String path) {
		Node ret = byPath.get(path);
		if( ret != null ) {
			ret.touch();
		}
		return ret;
	}

	/**
	 * A fileid that can't be got back from the path alone: one that isn't the path's hash
	 * (a rename, or a clash), or a path that doesn't fit in a handle.
	 */
	private boolean needsStore(long id, String path) {
		return id != hash(path) || (owner != null && !owner.pathFits(path));
	}

	/**
	 * Keep the identities in a file, read it now.
	 *
	 * @param maxNodes the most nodes kept in memory (the idle ones go first); 0 for no limit
	 */
	public synchronized void open(NodeStore s, int maxNodes) throws IOException {
		close();
		Map<Long, String> loaded = s.load();
		stored.clear();
		stored.putAll(loaded);
		reindex();
		s.compact(stored);
		store = s;
		this.maxNodes = maxNodes;
	}

	/** Only limit the nodes in memory (no file). */
	public synchronized void setMaxNodes(int maxNodes) {
		this.maxNodes = maxNodes;
	}

	public synchronized int getMaxNodes() {
		return maxNodes;
	}

	/** @return how many identities are kept in the file */
	public synchronized int storedSize() {
		return stored.size();
	}

	private void reindex() {
		storedByPath.clear();
		for (Map.Entry<Long, String> e : stored.entrySet()) {
			storedByPath.put(e.getValue(), e.getKey());
		}
	}

	/** Write what is waiting to the file. */
	public void flush() throws IOException {
		NodeStore s;
		synchronized (this) {
			s = store;
		}
		if( s != null ) {
			s.flush();
		}
	}

	public synchronized void close() throws IOException {
		NodeStore s = store;
		store = null;
		if( s != null ) {
			s.flush();
			s.close();
		}
	}

	/**
	 * Drop the nodes nobody asked for lately when there are more than the limit: not the root,
	 * not one with an open or a lock, and (with no file) only one whose fileid is its path's hash,
	 * since that is the only fileid that comes back.
	 */
	public void trim(boolean force) {
		trim(System.currentTimeMillis(), force);
	}

	synchronized void trim(long now, boolean force) {
		if( maxNodes <= 0 || byId.size() <= maxNodes || (!force && now < nextTrim) ) {
			return;
		}
		nextTrim = now+5000;
		List<Node> candidates = new ArrayList<Node>();
		for (Node n : byId.values()) {
			if( n.isRoot() || now-n.getLastUsed() < IDLE_BEFORE_DROP || !n.opens.isEmpty() || !n.locks.isEmpty() ) {
				continue;
			}
			// an id that is not its path's hash comes back only from the node file (decision D42): with no file
			// (no state folder) the entry in memory is lost on a restart, so such a node is kept
			boolean comesBack = n.getFileid() == hash(n.getPath()) || (store != null && stored.containsKey(n.getFileid()));
			if( !comesBack ) {
				continue;
			}
			candidates.add(n);
		}
		candidates.sort(Comparator.comparingLong(Node::getLastUsed));
		int target = (int) ((long) maxNodes*9/10);
		for (Node n : candidates) {
			if( byId.size() <= target ) {
				break;
			}
			byId.remove(n.getFileid());
			byPath.remove(n.getPath());
		}
	}

	/**
	 * Forget stored fileids whose file is gone (found out when it is looked for, and by this
	 * after a start): they would otherwise be kept for ever.
	 */
	public void pruneMissing() {
		if( owner == null ) {
			return;
		}
		List<Map.Entry<Long, String>> all;
		synchronized (this) {
			all = copy(new ArrayList<Map.Entry<Long, String>>(stored.entrySet()));
		}
		for (Map.Entry<Long, String> e : all) {
			try {
				if( !owner.resolve(e.getValue()).exists() ) {
					synchronized (this) {
						if( e.getValue().equals(stored.get(e.getKey())) ) {
							forgetStored(e.getKey());
						}
					}
				}
			} catch (IOException ex) {
				// can't tell: keep it
			}
		}
	}

	private static List<Map.Entry<Long, String>> copy(List<Map.Entry<Long, String>> in) {
		List<Map.Entry<Long, String>> ret = new ArrayList<Map.Entry<Long, String>>(in.size());
		for (Map.Entry<Long, String> e : in) {
			ret.add(new java.util.AbstractMap.SimpleImmutableEntry<Long, String>(e.getKey(), e.getValue()));
		}
		return ret;
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
		// the stored identities move too, those not in memory included
		NodeStore.rename(stored, from, to);
		for (Node n : moved) {
			if( needsStore(n.getFileid(), n.getPath()) ) {
				stored.put(n.getFileid(), n.getPath());
			}
		}
		reindex();
		if( store != null ) {
			// after the R line, so the added lines win on replay
			store.renamed(from, to);
			for (Node n : moved) {
				if( stored.containsKey(n.getFileid()) ) {
					store.add(n.getFileid(), n.getPath());
				}
			}
		}
	}

	/** A file or folder was removed: forget its node (handles to it become stale). */
	public synchronized void removed(String path) {
		Node n = byPath.remove(path);
		if( n != null ) {
			byId.remove(n.getFileid());
		}
		if( storedByPath.containsKey(path) ) {
			NodeStore.removePath(stored, path);
			reindex();
			if( store != null ) {
				store.removed(path);
			}
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
