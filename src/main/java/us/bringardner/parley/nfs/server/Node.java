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

import java.util.ArrayList;
import java.util.List;

/**
 * A file or folder the server has handed out a file handle for. A FileSource has no inode, so
 * the node is what keeps a file's handle and fileid while it is renamed.
 *
 * @author Tony Bringardner
 */
public final class Node {

	private final long fileid;
	private volatile String path;
	private volatile long changes;
	/** The opens of this file, guarded by the StateManager. */
	final List<StateManager.OpenState> opens = new ArrayList<StateManager.OpenState>();

	Node(long fileid, String path) {
		this.fileid = fileid;
		this.path = path;
	}

	/** @return the fileid attribute, also in the file handle */
	public long getFileid() {
		return fileid;
	}

	/** @return the path inside the export, "" for its root, folders separated by / */
	public String getPath() {
		return path;
	}

	void setPath(String path) {
		this.path = path;
	}

	public boolean isRoot() {
		return path.isEmpty();
	}

	/** @return the last part of the path, "" for the root */
	public String getName() {
		int i = path.lastIndexOf('/');
		return i < 0 ? path : path.substring(i+1);
	}

	/** @return the parent folder's path, null for the root */
	public String getParentPath() {
		if( path.isEmpty() ) {
			return null;
		}
		int i = path.lastIndexOf('/');
		return i < 0 ? "" : path.substring(0, i);
	}

	/** Count a change made through this server (part of the change attribute). */
	void changed() {
		changes++;
	}

	long getChanges() {
		return changes;
	}

	@Override
	public String toString() {
		return "/"+path+" ("+fileid+")";
	}
}
