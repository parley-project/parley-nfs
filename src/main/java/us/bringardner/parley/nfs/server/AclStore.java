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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import us.bringardner.parley.files.FileSource;

/**
 * The access control lists of one export (decision D51), kept in a file in the state folder:
 * a journal of lines, each {@code S <hex fileid> <path> <entries>} (a file's list), {@code X <hex fileid>}
 * (its list taken away) or {@code R <from> <to>} (a rename, so the stored path follows), the fields
 * separated by a tab with {@code \}, tab, newline and return escaped, and each entry four fields:
 * type, flags and mask in hex, and who. The path is kept so an entry whose file is gone can be
 * dropped. It is read once at start-up and appended to through a FileSource; the server rewrites it
 * whenever it opens it.
 *
 * @author Tony Bringardner
 */
public final class AclStore {

	private static final String HEADER = "#parley-nfs-acls 1";

	private static final class Entry {
		final String path;
		final List<Ace> acl;

		Entry(String path, List<Ace> acl) {
			this.path = path;
			this.acl = acl;
		}
	}

	private final FileSource file;
	private final Map<Long, Entry> entries = new ConcurrentHashMap<Long, Entry>();
	private OutputStream out;
	private boolean dirty;
	private IOException failed;

	public AclStore(FileSource file) {
		this.file = file;
	}

	/** Read the journal (none yet is no lists) and rewrite it tidily, ready for appending. */
	public synchronized void open() throws IOException {
		entries.clear();
		if( file.exists() ) {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
				String line = in.readLine();
				if( line == null || !line.equals(HEADER) ) {
					throw new IOException(file+" isn't a parley-nfs ACL file");
				}
				while( (line = in.readLine()) != null ) {
					replay(line);
				}
			}
		}
		compact();
	}

	private void replay(String line) {
		String[] f = line.split("\t", -1);
		try {
			if( f[0].equals("S") && f.length >= 3 && (f.length-3) % 4 == 0 ) {
				List<Ace> acl = new ArrayList<Ace>();
				for (int i = 3; i < f.length; i += 4) {
					acl.add(new Ace(Integer.parseInt(f[i], 16), Integer.parseInt(f[i+1], 16),
							Integer.parseUnsignedInt(f[i+2], 16), NodeStore.unescape(f[i+3])));
				}
				entries.put(Long.parseUnsignedLong(f[1], 16), new Entry(NodeStore.unescape(f[2]), Collections.unmodifiableList(acl)));
			} else if( f[0].equals("X") && f.length == 2 ) {
				entries.remove(Long.parseUnsignedLong(f[1], 16));
			} else if( f[0].equals("R") && f.length == 3 ) {
				rename(NodeStore.unescape(f[1]), NodeStore.unescape(f[2]));
			}
			// a line this version doesn't know is skipped
		} catch (NumberFormatException e) {
			// a torn last line after a crash: nothing to do with it
		}
	}

	private void compact() throws IOException {
		close();
		FileSource parent = file.getParentFile();
		if( parent != null && !parent.exists() ) {
			parent.mkdirs();
		}
		FileSource tmp = parent == null ? file : parent.getChild(file.getName()+".tmp");
		StringBuilder b = new StringBuilder(HEADER).append('\n');
		for (Map.Entry<Long, Entry> e : entries.entrySet()) {
			b.append(line(e.getKey(), e.getValue().path, e.getValue().acl));
		}
		try (OutputStream o = tmp.getOutputStream(false)) {
			o.write(b.toString().getBytes(StandardCharsets.UTF_8));
		}
		if( tmp != file && !tmp.renameTo(file, true) ) {
			throw new IOException("can't replace "+file+" with "+tmp);
		}
		out = file.getOutputStream(true);
	}

	private static String line(long id, String path, List<Ace> acl) {
		StringBuilder b = new StringBuilder("S\t").append(Long.toHexString(id)).append('\t').append(NodeStore.escape(path));
		for (Ace a : acl) {
			b.append('\t').append(Integer.toHexString(a.getType())).append('\t').append(Integer.toHexString(a.getFlags()))
					.append('\t').append(Integer.toHexString(a.getMask())).append('\t').append(NodeStore.escape(a.getWho()));
		}
		return b.append('\n').toString();
	}

	/** @return the file's list, null when it has none */
	public List<Ace> get(long fileid) {
		Entry e = entries.get(fileid);
		return e == null ? null : e.acl;
	}

	public int size() {
		return entries.size();
	}

	/** Give a file this list (an empty list is a list that refuses everyone but its owner's ACL rights). */
	public synchronized void set(long fileid, String path, List<Ace> acl) {
		List<Ace> copy = Collections.unmodifiableList(new ArrayList<Ace>(acl));
		entries.put(fileid, new Entry(path, copy));
		write(line(fileid, path, copy));
	}

	/** Take a file's list away (it is removed, or overwritten by a rename). */
	public synchronized void clear(long fileid) {
		if( entries.remove(fileid) != null ) {
			write("X\t"+Long.toHexString(fileid)+"\n");
		}
	}

	/** A file or folder was renamed: the paths kept for it and what is under it follow. */
	public synchronized void renamed(String from, String to) {
		if( rename(from, to) ) {
			write("R\t"+NodeStore.escape(from)+"\t"+NodeStore.escape(to)+"\n");
		}
	}

	private boolean rename(String from, String to) {
		boolean changed = false;
		String prefix = from+"/";
		for (Map.Entry<Long, Entry> e : entries.entrySet()) {
			String p = e.getValue().path;
			if( p.equals(from) ) {
				e.setValue(new Entry(to, e.getValue().acl));
				changed = true;
			} else if( p.startsWith(prefix) ) {
				e.setValue(new Entry(to+p.substring(from.length()), e.getValue().acl));
				changed = true;
			}
		}
		return changed;
	}

	/**
	 * Drop the lists of files that are gone, so a new file at their path doesn't get them.
	 *
	 * @return how many were dropped
	 */
	public int pruneMissing(Export export) {
		int dropped = 0;
		for (Map.Entry<Long, Entry> e : new ArrayList<Map.Entry<Long, Entry>>(entries.entrySet())) {
			try {
				if( !export.resolve(e.getValue().path).exists() ) {
					clear(e.getKey());
					dropped++;
				}
			} catch (IOException | RuntimeException ex) {
				// can't tell: keep it
			}
		}
		return dropped;
	}

	private void write(String line) {
		if( out == null ) {
			return;
		}
		try {
			out.write(line.getBytes(StandardCharsets.UTF_8));
			dirty = true;
		} catch (IOException e) {
			failed = e;
		}
	}

	/** Push what was written out to the file. */
	public synchronized void flush() throws IOException {
		if( failed != null ) {
			IOException e = failed;
			failed = null;
			throw e;
		}
		if( out != null && dirty ) {
			out.flush();
			dirty = false;
		}
	}

	public synchronized void close() throws IOException {
		if( out != null ) {
			try {
				out.close();
			} finally {
				out = null;
			}
		}
	}
}
