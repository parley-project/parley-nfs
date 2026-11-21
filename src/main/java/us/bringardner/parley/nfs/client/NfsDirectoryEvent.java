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

package us.bringardner.parley.nfs.client;

/** A change another client made to a watched folder (D93). */
public final class NfsDirectoryEvent {

	public enum Type {
		/** a file or folder was created in the folder, or moved into it */
		ADDED,
		/** a file or folder was removed from the folder, or moved out of it */
		REMOVED,
		/** a file or folder in the folder got another name */
		RENAMED
	}

	private final Type type;
	private final String directory;
	private final String name;
	private final String newName;

	NfsDirectoryEvent(Type type, String directory, String name, String newName) {
		this.type = type;
		this.directory = directory;
		this.name = name;
		this.newName = newName;
	}

	public Type getType() {
		return type;
	}

	/** @return the path of the watched folder, as it was given to {@code watchDirectory} */
	public String getDirectory() {
		return directory;
	}

	/** @return the name of the entry: the one added, the one removed, or the old name of a rename */
	public String getName() {
		return name;
	}

	/** @return the new name of a rename, null for the other types */
	public String getNewName() {
		return newName;
	}

	@Override
	public String toString() {
		return type+" "+directory+"/"+name+(newName == null ? "" : " -> "+newName);
	}
}
