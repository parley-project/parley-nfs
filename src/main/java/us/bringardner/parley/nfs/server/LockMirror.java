/*
 * Copyright 2026 Tony Bringardner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package us.bringardner.parley.nfs.server;

import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.lock.FileSourceLock;

/**
 * Byte-range locks of NFS clients, held as FileSource locks too (decision D68), so that another
 * Parley program that locks the same file (a mail server and its mailbox, say) sees them, and an
 * NFS client sees its locks.
 * <p>
 * A FileSource refuses a second lock that overlaps one this JVM holds, whoever asks, and a
 * lock can't change its size or type. So the mirror doesn't hold one lock per NFS lock: for each
 * file it holds the <em>union</em> of all the NFS locks on it, cut into pieces that don't overlap,
 * each as shared if only shared NFS locks cover it, else exclusive. After every change to a file's
 * NFS locks, {@link #sync} makes the held pieces the pieces that are wanted: it releases the ones
 * that aren't wanted any more and takes the new ones. A piece that is kept is never let go, but one
 * that is cut is released and its remainder taken again, so there is a short time when a foreign
 * holder could take it; that is logged when it happens.
 * <p>
 * Always called with the {@link StateManager}'s monitor held, which keeps the NFS side and the
 * mirror together. A file system that can't be asked (the file is gone, a FileSource without locks)
 * is logged once per file and the NFS lock stands without a mirror: the mirror never makes a lock
 * fail for that.
 *
 * @author Tony Bringardner
 */
final class LockMirror {

	/** the end of a range that reaches to the end of the file */
	static final long EOF = Long.MAX_VALUE;

	/** A piece of a file that is locked: from start up to (not including) end. */
	static final class Run {
		final long start;
		final long end;
		final boolean write;

		Run(long start, long end, boolean write) {
			this.start = start;
			this.end = end;
			this.write = write;
		}

		boolean same(Run o) {
			return start == o.start && end == o.end && write == o.write;
		}

		/** @return the size a FileSource lock takes: 0 is to the end of the file */
		long size() {
			return end == EOF ? 0 : end-start;
		}

		@Override
		public String toString() {
			return (write ? "exclusive " : "shared ")+start+"-"+(end == EOF ? "end" : Long.toString(end));
		}
	}

	private static final class Held {
		final Run run;
		final FileSourceLock lock;

		Held(Run run, FileSourceLock lock) {
			this.run = run;
			this.lock = lock;
		}
	}

	/** how a node's file is found */
	interface Files {
		FileSource resolve(Node node) throws IOException;
	}

	private final Files files;
	private final Consumer<String> warnings;
	private final Map<Node, List<Held>> held = new HashMap<Node, List<Held>>();
	/** nodes whose file couldn't be locked: said once */
	private final Set<Node> said = new HashSet<Node>();

	LockMirror(Files files, Consumer<String> warnings) {
		this.files = files;
		this.warnings = warnings;
	}

	/**
	 * Make the FileSource locks of a file the ones its NFS locks want.
	 * @return null, or the piece that another holder keeps us from locking (the pieces taken up to
	 *         then are held: the caller takes its change back and syncs again)
	 */
	synchronized Run sync(Node node) {
		List<Run> want = runs(node);
		List<Held> have = held.get(node);
		if( have == null ) {
			have = new ArrayList<Held>();
		}
		for (Iterator<Held> i = have.iterator(); i.hasNext();) {
			Held h = i.next();
			boolean keep = false;
			for (Run r : want) {
				if( r.same(h.run) ) {
					keep = true;
					break;
				}
			}
			if( !keep ) {
				release(node, h);
				i.remove();
			}
		}
		Run failed = null;
		FileSource source = null;
		for (Run r : want) {
			boolean have1 = false;
			for (Held h : have) {
				if( h.run.same(r) ) {
					have1 = true;
					break;
				}
			}
			if( have1 ) {
				continue;
			}
			try {
				if( source == null ) {
					source = files.resolve(node);
				}
				FileSourceLock lock = source.tryLock(r.start, r.size(), !r.write);
				if( lock == null ) {
					failed = r;
					break;
				}
				have.add(new Held(r, lock));
			} catch (OverlappingFileLockException e) {
				// this JVM holds an overlapping lock that isn't ours: another program's
				failed = r;
				break;
			} catch (IOException | RuntimeException e) {
				cantLock(node, e);
				break;
			}
		}
		if( have.isEmpty() ) {
			held.remove(node);
		} else {
			held.put(node, have);
		}
		return failed;
	}

	/** {@link #sync} for a change that can't be refused: a piece we can't take again is logged. */
	synchronized void resync(Node node) {
		Run failed = sync(node);
		if( failed != null ) {
			warnings.accept("Another program locked "+failed+" of "+describe(node)
				+" while a lock of an NFS client changed; the client still thinks it holds that part");
		}
	}

	/**
	 * LOCKT: does another program hold a lock that stops this one?
	 * @return true if one does
	 */
	synchronized boolean test(Node node, boolean write, long start, long end) {
		if( Long.compareUnsigned(start, EOF) > 0 || start < 0 ) {
			return false;
		}
		long e = end == -1L || end < 0 ? EOF : end;
		List<long[]> pieces = new ArrayList<long[]>();
		pieces.add(new long[] { start, e });
		List<Held> have = held.get(node);
		if( have != null ) {
			// what we hold ourselves can't be asked: the JVM would refuse it
			for (Held h : have) {
				List<long[]> next = new ArrayList<long[]>();
				for (long[] p : pieces) {
					if( h.run.end <= p[0] || h.run.start >= p[1] ) {
						next.add(p);
						continue;
					}
					if( h.run.start > p[0] ) {
						next.add(new long[] { p[0], h.run.start });
					}
					if( h.run.end < p[1] ) {
						next.add(new long[] { h.run.end, p[1] });
					}
				}
				pieces = next;
			}
		}
		FileSource source = null;
		for (long[] p : pieces) {
			try {
				if( source == null ) {
					source = files.resolve(node);
				}
				FileSourceLock lock = source.tryLock(p[0], p[1] == EOF ? 0 : p[1]-p[0], !write);
				if( lock == null ) {
					return true;
				}
				release(node, new Held(null, lock));
			} catch (OverlappingFileLockException ex) {
				return true;
			} catch (IOException | RuntimeException ex) {
				cantLock(node, ex);
				return false;
			}
		}
		return false;
	}

	/** Let go of everything (the server stops). */
	synchronized void close() {
		for (Map.Entry<Node, List<Held>> e : held.entrySet()) {
			for (Held h : e.getValue()) {
				release(e.getKey(), h);
			}
		}
		held.clear();
	}

	/** @return how many pieces are held, for tests */
	synchronized int getHeldCount() {
		int ret = 0;
		for (List<Held> l : held.values()) {
			ret += l.size();
		}
		return ret;
	}

	/** @return the pieces held of a node's file, for tests */
	synchronized List<Run> getHeld(Node node) {
		List<Run> ret = new ArrayList<Run>();
		List<Held> have = held.get(node);
		if( have != null ) {
			for (Held h : have) {
				ret.add(h.run);
			}
		}
		return ret;
	}

	private void release(Node node, Held h) {
		try {
			h.lock.release();
		} catch (IOException | RuntimeException e) {
			cantLock(node, e);
		}
	}

	private void cantLock(Node node, Exception e) {
		if( said.add(node) ) {
			warnings.accept("The locks of NFS clients on "+describe(node)+" aren't held as file locks: "+e);
		}
	}

	private static String describe(Node node) {
		return node.getExport().getName()+":/"+node.getPath();
	}

	/**
	 * The pieces of a node's file that its NFS locks cover, in order, none overlapping: exclusive
	 * where any exclusive lock covers, else shared; neighbors of one type joined.
	 */
	static List<Run> runs(Node node) {
		List<long[]> all = new ArrayList<long[]>();
		TreeSet<Long> points = new TreeSet<Long>();
		for (StateManager.LockState l : node.locks) {
			for (StateManager.Range r : l.ranges) {
				if( r.start < 0 ) {
					// past 2^63: no FileSource can lock it
					continue;
				}
				long e = r.end == -1L || r.end < 0 ? EOF : r.end;
				if( e <= r.start ) {
					continue;
				}
				all.add(new long[] { r.start, e, r.write ? 1 : 0 });
				points.add(r.start);
				points.add(e);
			}
		}
		List<Run> ret = new ArrayList<Run>();
		Long prev = null;
		for (Long p : points) {
			if( prev != null ) {
				boolean covered = false;
				boolean write = false;
				for (long[] r : all) {
					if( r[0] <= prev && r[1] >= p ) {
						covered = true;
						write |= r[2] == 1;
					}
				}
				if( covered ) {
					Run last = ret.isEmpty() ? null : ret.get(ret.size()-1);
					if( last != null && last.end == prev && last.write == write ) {
						ret.set(ret.size()-1, new Run(last.start, p, write));
					} else {
						ret.add(new Run(prev, p, write));
					}
				}
			}
			prev = p;
		}
		return ret;
	}
}
