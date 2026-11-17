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
import java.util.LinkedHashMap;
import java.util.Map;

import us.bringardner.parley.files.FileSource;

/**
 * The file a {@link NodeTable} keeps its identities in (decision D41): a journal of lines, each
 * {@code A <hex fileid> <path>} (a node), {@code R <from> <to>} (a rename) or {@code D <path>}
 * (a removal), the fields separated by a tab with {@code \}, tab, newline and return escaped.
 * It is read once at start-up and then appended to through a FileSource; the server rewrites it
 * (compacts it) whenever it opens it.
 *
 * @author Tony Bringardner
 */
final class NodeStore {

	private static final String HEADER = "#parley-nfs-nodes 1";

	private final FileSource file;
	private OutputStream out;
	private boolean dirty;

	NodeStore(FileSource file) {
		this.file = file;
	}

	/** @return the fileid to path pairs the journal ends with, empty if there is no journal yet */
	Map<Long, String> load() throws IOException {
		Map<Long, String> ret = new LinkedHashMap<Long, String>();
		if( !file.exists() ) {
			return ret;
		}
		try (BufferedReader in = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
			String line = in.readLine();
			if( line == null || !line.equals(HEADER) ) {
				throw new IOException(file+" isn't a parley-nfs node file");
			}
			while( (line = in.readLine()) != null ) {
				String[] f = line.split("\t", -1);
				try {
					if( f[0].equals("A") && f.length == 3 ) {
						long id = Long.parseUnsignedLong(f[1], 16);
						String path = unescape(f[2]);
						removePath(ret, path);
						ret.put(id, path);
					} else if( f[0].equals("R") && f.length == 3 ) {
						rename(ret, unescape(f[1]), unescape(f[2]));
					} else if( f[0].equals("D") && f.length == 2 ) {
						removePath(ret, unescape(f[1]));
					}
					// a line this version doesn't know is skipped
				} catch (NumberFormatException e) {
					// a torn last line after a crash: nothing to do with it
				}
			}
		}
		return ret;
	}

	/** Replace the journal with these pairs and keep it open for appending. */
	synchronized void compact(Map<Long, String> nodes) throws IOException {
		close();
		FileSource parent = file.getParentFile();
		if( parent != null && !parent.exists() ) {
			parent.mkdirs();
		}
		FileSource tmp = file.getParentFile() == null ? file : file.getParentFile().getChild(file.getName()+".tmp");
		StringBuilder b = new StringBuilder(HEADER).append('\n');
		for (Map.Entry<Long, String> e : nodes.entrySet()) {
			b.append("A\t").append(Long.toHexString(e.getKey())).append('\t').append(escape(e.getValue())).append('\n');
		}
		try (OutputStream o = tmp.getOutputStream(false)) {
			o.write(b.toString().getBytes(StandardCharsets.UTF_8));
		}
		if( tmp != file ) {
			if( !tmp.renameTo(file, true) ) {
				throw new IOException("can't replace "+file+" with "+tmp);
			}
		}
		out = file.getOutputStream(true);
	}

	synchronized void add(long id, String path) {
		write("A\t"+Long.toHexString(id)+"\t"+escape(path)+"\n");
	}

	synchronized void renamed(String from, String to) {
		write("R\t"+escape(from)+"\t"+escape(to)+"\n");
	}

	synchronized void removed(String path) {
		write("D\t"+escape(path)+"\n");
	}

	private void write(String line) {
		if( out == null ) {
			return;
		}
		try {
			out.write(line.getBytes(StandardCharsets.UTF_8));
			dirty = true;
		} catch (IOException e) {
			// the next flush says so
			failed = e;
		}
	}

	private IOException failed;

	/** Push what was written out to the file. */
	synchronized void flush() throws IOException {
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

	synchronized void close() throws IOException {
		if( out != null ) {
			try {
				out.close();
			} finally {
				out = null;
			}
		}
	}

	/** Remove the node at a path and under it. */
	static void removePath(Map<Long, String> nodes, String path) {
		String prefix = path+"/";
		nodes.values().removeIf(p -> p.equals(path) || p.startsWith(prefix));
	}

	/** Move the nodes at and under a path, first removing those at and under the new path. */
	static void rename(Map<Long, String> nodes, String from, String to) {
		removePath(nodes, to);
		String prefix = from+"/";
		for (Map.Entry<Long, String> e : nodes.entrySet()) {
			String p = e.getValue();
			if( p.equals(from) ) {
				e.setValue(to);
			} else if( p.startsWith(prefix) ) {
				e.setValue(to+p.substring(from.length()));
			}
		}
	}

	static String escape(String s) {
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '\\': b.append("\\\\"); break;
			case '\t': b.append("\\t"); break;
			case '\n': b.append("\\n"); break;
			case '\r': b.append("\\r"); break;
			default: b.append(c);
			}
		}
		return b.toString();
	}

	static String unescape(String s) {
		if( s.indexOf('\\') < 0 ) {
			return s;
		}
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if( c == '\\' && i+1 < s.length() ) {
				char n = s.charAt(++i);
				b.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
			} else {
				b.append(c);
			}
		}
		return b.toString();
	}
}
