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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.iam.EntityId;
import us.bringardner.parley.iam.ProtectedResource;
import us.bringardner.parley.nfs.protocol.Nfs4;

/**
 * A folder the server shares: the root of what clients see (PUTROOTFH), on any FileSource.
 * <p>
 * File handles: version (1 byte), fileid (8), then the path inside the export as UTF-8 when it
 * fits in the {@value Nfs4#NFS4_FHSIZE} bytes. While the server runs the fileid finds the node
 * (a renamed file keeps its handle); after a restart the path finds the file again. A handle
 * without the path (a long path) is stale after a restart.
 *
 * @author Tony Bringardner
 */
public class Export implements ProtectedResource {

	/** The type of resource an export is, for permission checks. */
	public static final String RESOURCE_TYPE = "nfs-export";
	static final int HANDLE_VERSION = 1;
	static final int HANDLE_HEADER = 9;
	/** RFC 7530 leaves the maximum name length to the server; 255 is what most file systems allow. */
	public static final int MAX_NAME = 255;

	private final String name;
	private final FileSource root;
	private final boolean readOnly;
	private final long fsidMajor;
	private final long fsidMinor;
	private final NodeTable nodes = new NodeTable();
	private final EntityId id;

	/**
	 * @param name the export's name (for logs and permission checks)
	 * @param root its root folder
	 * @param readOnly true to refuse every change
	 */
	public Export(String name, FileSource root, boolean readOnly) {
		this.name = Objects.requireNonNull(name, "name");
		this.root = Objects.requireNonNull(root, "root");
		this.readOnly = readOnly;
		this.fsidMajor = 0x50524c59L; // "PRLY"
		this.fsidMinor = NodeTable.hash(name) & 0x7fffffffL;
		this.id = EntityId.of(RESOURCE_TYPE+":"+name);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getType() {
		return RESOURCE_TYPE;
	}

	@Override
	public EntityId getId() {
		return id;
	}

	/** The framework doesn't know who owns an export; the registry decides who may use it. */
	@Override
	public EntityId getOwnerId() {
		return null;
	}

	public FileSource getRoot() {
		return root;
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public long getFsidMajor() {
		return fsidMajor;
	}

	public long getFsidMinor() {
		return fsidMinor;
	}

	public NodeTable getNodes() {
		return nodes;
	}

	public Node getRootNode() {
		return nodes.get("");
	}

	/** @return the FileSource at a node's path (it may not exist) */
	public FileSource resolve(Node node) throws IOException {
		return resolve(node.getPath());
	}

	/** @return the FileSource at a path inside the export (it may not exist) */
	public FileSource resolve(String path) throws IOException {
		FileSource ret = root;
		if( path.isEmpty() ) {
			return ret;
		}
		int start = 0;
		while( start <= path.length() ) {
			int end = path.indexOf('/', start);
			if( end < 0 ) {
				end = path.length();
			}
			ret = ret.getChild(path.substring(start, end));
			start = end+1;
		}
		return ret;
	}

	/** @return the path of a name in a folder */
	public static String childPath(Node dir, String name) {
		return dir.isRoot() ? name : dir.getPath()+"/"+name;
	}

	/**
	 * Check a component name (LOOKUP, CREATE, OPEN, REMOVE, RENAME): RFC 7530 section 12.
	 * @throws Nfs4Exception NFS4ERR_INVAL for an empty name, NFS4ERR_NAMETOOLONG, NFS4ERR_BADNAME
	 * for . .. or a name with / or NUL (it could leave the folder)
	 */
	public static void checkName(String name) throws Nfs4Exception {
		if( name.isEmpty() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "empty name");
		}
		if( name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_NAMETOOLONG);
		}
		if( name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0 ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BADNAME, name);
		}
	}

	// -------------------------------------------------------------- file handles

	public byte[] toHandle(Node node) {
		byte[] path = node.getPath().getBytes(StandardCharsets.UTF_8);
		boolean withPath = HANDLE_HEADER+path.length <= Nfs4.NFS4_FHSIZE;
		ByteBuffer b = ByteBuffer.allocate(HANDLE_HEADER+(withPath ? path.length : 0));
		b.put((byte) (withPath ? HANDLE_VERSION : HANDLE_VERSION | 0x80));
		b.putLong(node.getFileid());
		if( withPath ) {
			b.put(path);
		}
		return b.array();
	}

	/**
	 * @return the node of a handle
	 * @throws Nfs4Exception NFS4ERR_BADHANDLE if it isn't one of this server's handles,
	 * NFS4ERR_STALE if its file is gone
	 */
	public Node fromHandle(byte[] handle) throws Nfs4Exception {
		if( handle.length < HANDLE_HEADER || handle.length > Nfs4.NFS4_FHSIZE
				|| (handle[0] & 0x7f) != HANDLE_VERSION ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BADHANDLE);
		}
		boolean withPath = (handle[0] & 0x80) == 0;
		ByteBuffer b = ByteBuffer.wrap(handle);
		b.get();
		long fileid = b.getLong();
		Node ret = nodes.find(fileid);
		if( ret == null && withPath ) {
			String path = new String(handle, HANDLE_HEADER, handle.length-HANDLE_HEADER, StandardCharsets.UTF_8);
			if( !validPath(path) ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_BADHANDLE);
			}
			try {
				if( !resolve(path).exists() ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_STALE);
				}
			} catch (IOException e) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_STALE, e.getMessage());
			}
			ret = nodes.get(path, fileid);
		}
		if( ret == null ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_STALE);
		}
		return ret;
	}

	/** A path from a handle must not leave the export. */
	private static boolean validPath(String path) {
		if( path.isEmpty() ) {
			return true;
		}
		for (String part : path.split("/", -1)) {
			if( part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('\0') >= 0 ) {
				return false;
			}
		}
		return true;
	}

	@Override
	public String toString() {
		return name+" ("+root.getAbsolutePath()+(readOnly ? ", read only" : "")+")";
	}
}
