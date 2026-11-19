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

import us.bringardner.parley.nfs.protocol.Nfs4;

/**
 * What the server said about a file: its handle and the attributes the client asks for.
 * A value the server didn't send is -1 (or null).
 */
public final class NfsFileInfo {

	private final String name;
	private final byte[] handle;
	private final int type;
	private final long size;
	private final long fileId;
	private final int mode;
	private final long modifyTime;

	NfsFileInfo(String name, byte[] handle, int type, long size, long fileId, int mode, long modifyTime) {
		this.name = name;
		this.handle = handle;
		this.type = type;
		this.size = size;
		this.fileId = fileId;
		this.mode = mode;
		this.modifyTime = modifyTime;
	}

	/** @return the name in its folder (null for what a lookup of a path found) */
	public String getName() {
		return name;
	}

	/** @return the file handle (null for a READDIR entry: look the name up to get one) */
	public byte[] getHandle() {
		return handle;
	}

	/** @return one of Nfs4.NF4REG, NF4DIR, NF4LNK ... */
	public int getType() {
		return type;
	}

	public boolean isDirectory() {
		return type == Nfs4.NF4DIR;
	}

	public boolean isFile() {
		return type == Nfs4.NF4REG;
	}

	public boolean isLink() {
		return type == Nfs4.NF4LNK;
	}

	public long getSize() {
		return size;
	}

	public long getFileId() {
		return fileId;
	}

	/** @return the permission bits, -1 when not sent */
	public int getMode() {
		return mode;
	}

	/** @return the modify time in milliseconds since 1970, -1 when not sent */
	public long getModifyTime() {
		return modifyTime;
	}

	@Override
	public String toString() {
		return "NfsFileInfo[name="+name+", type="+type+", size="+size+", fileid="+fileId+", mode="+Integer.toOctalString(mode)+"]";
	}
}
