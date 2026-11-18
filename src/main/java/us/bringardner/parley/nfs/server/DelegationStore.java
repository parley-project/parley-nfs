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
import java.util.List;

import us.bringardner.parley.files.FileSource;

/**
 * The file that keeps the delegations clients hold, so that a restarted server can let them be reclaimed
 * (decision D67; the other client state stays in memory, D7). A journal of lines, each
 * {@code G <client> <verifier> <principal> <export> <fileid> <r|w>} (a delegation given) or
 * {@code X <client> <export> <fileid>} (one gone), fields separated by a tab with {@code \}, tab, newline and
 * return escaped. It is read once at start-up and rewritten (compacted) then; every change is flushed at once,
 * because a delegation the client holds must be on disk before the client is told about it.
 *
 * @author Tony Bringardner
 */
final class DelegationStore {

	private static final String HEADER = "#parley-nfs-delegations 1";

	/** A delegation as the file keeps it. */
	static final class Entry {
		/** the client's name: its nfs_client_id4 id, as the StateManager keys it */
		final String client;
		/** the verifier the client gave to SETCLIENTID, in hex: another one is a client that rebooted */
		final String verifier;
		final String principal;
		final String export;
		final long fileid;
		final boolean write;
		/** true once the client has taken the delegation back (CLAIM_PREVIOUS); not kept in the file */
		volatile boolean reclaimed;

		Entry(String client, String verifier, String principal, String export, long fileid, boolean write) {
			this.client = client;
			this.verifier = verifier;
			this.principal = principal;
			this.export = export;
			this.fileid = fileid;
			this.write = write;
		}

		boolean is(String client, String export, long fileid) {
			return this.fileid == fileid && this.client.equals(client) && this.export.equals(export);
		}
	}

	private final FileSource file;
	private OutputStream out;

	DelegationStore(FileSource file) {
		this.file = file;
	}

	/** @return the delegations the journal ends with, empty if there is no journal yet */
	List<Entry> load() throws IOException {
		List<Entry> ret = new ArrayList<Entry>();
		if( !file.exists() ) {
			return ret;
		}
		try (BufferedReader in = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
			String line = in.readLine();
			if( line == null || !line.equals(HEADER) ) {
				throw new IOException(file+" isn't a parley-nfs delegation file");
			}
			while( (line = in.readLine()) != null ) {
				String[] f = line.split("\t", -1);
				try {
					if( f[0].equals("G") && f.length == 7 ) {
						String client = NodeStore.unescape(f[1]);
						String export = NodeStore.unescape(f[4]);
						long fileid = Long.parseUnsignedLong(f[5], 16);
						ret.removeIf(e -> e.is(client, export, fileid));
						ret.add(new Entry(client, NodeStore.unescape(f[2]), NodeStore.unescape(f[3]), export, fileid, f[6].equals("w")));
					} else if( f[0].equals("X") && f.length == 4 ) {
						String client = NodeStore.unescape(f[1]);
						String export = NodeStore.unescape(f[2]);
						long fileid = Long.parseUnsignedLong(f[3], 16);
						ret.removeIf(e -> e.is(client, export, fileid));
					}
					// a line this version doesn't know is skipped
				} catch (NumberFormatException e) {
					// a torn last line after a crash: nothing to do with it
				}
			}
		}
		return ret;
	}

	/** Replace the journal with these delegations and keep it open for appending. */
	synchronized void compact(List<Entry> entries) throws IOException {
		close();
		FileSource parent = file.getParentFile();
		if( parent != null && !parent.exists() ) {
			parent.mkdirs();
		}
		FileSource tmp = parent == null ? file : parent.getChild(file.getName()+".tmp");
		StringBuilder b = new StringBuilder(HEADER).append('\n');
		for (Entry e : entries) {
			b.append(line(e));
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

	private static String line(Entry e) {
		return "G\t"+NodeStore.escape(e.client)+"\t"+NodeStore.escape(e.verifier)+"\t"+NodeStore.escape(e.principal)
				+"\t"+NodeStore.escape(e.export)+"\t"+Long.toHexString(e.fileid)+"\t"+(e.write ? "w" : "r")+"\n";
	}

	/** Keep a delegation that was given: written out before this returns. */
	synchronized void add(Entry e) throws IOException {
		write(line(e));
	}

	/** Forget a delegation. */
	synchronized void remove(String client, String export, long fileid) throws IOException {
		write("X\t"+NodeStore.escape(client)+"\t"+NodeStore.escape(export)+"\t"+Long.toHexString(fileid)+"\n");
	}

	private void write(String line) throws IOException {
		if( out == null ) {
			throw new IOException("the delegation file isn't open");
		}
		out.write(line.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	@Override
	public String toString() {
		return file.toString();
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
}
