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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.Rpc;
import us.bringardner.parley.nfs.rpc.XdrDecoder;
import us.bringardner.parley.nfs.rpc.XdrEncoder;
import us.bringardner.parley.nfs.rpc.XdrException;
import us.bringardner.parley.nfs.server.StateManager.ClientRecord;
import us.bringardner.parley.nfs.server.StateManager.LockDeniedException;
import us.bringardner.parley.nfs.server.StateManager.LockOwner;
import us.bringardner.parley.nfs.server.StateManager.LockState;
import us.bringardner.parley.nfs.server.StateManager.OpenOwner;
import us.bringardner.parley.nfs.server.StateManager.OpenState;
import us.bringardner.parley.nfs.server.StateManager.SeqOwner;
import us.bringardner.parley.nfs.server.StateManager.StateId;

/**
 * One COMPOUND procedure (RFC 7530 sections 15 and 16): its operations run in order with a
 * current and a saved file handle, until one fails.
 *
 * @author Tony Bringardner
 */
public class Compound {

	/** The most operations one COMPOUND may have (more is NFS4ERR_RESOURCE). */
	public static final int MAX_OPERATIONS = 128;

	/** The size of a READDIR4resok with no entries: the cookie verifier, the end of the list and eof. */
	private static final int READDIR_EMPTY = Nfs4.NFS4_VERIFIER_SIZE+8;

	private final NfsServer server;
	private final StateManager state;
	private final OpenFiles files;
	private final Attributes attributes;
	private final Caller caller;
	private final InetAddress client;
	private final int localPort;

	private Node current;
	private Node saved;
	private XdrDecoder in;
	private XdrEncoder out;
	/** where the status of the operation running now is in out */
	private int statusPos;
	/** true once an operation that mustn't run twice ran (its reply goes in the {@link ReplayCache}) */
	private boolean nonIdempotent;

	public Compound(NfsServer server, Caller caller, InetAddress client, int localPort) {
		this.server = server;
		this.state = server.getStateManager();
		this.files = server.getOpenFiles();
		this.attributes = server.getFileAttributes();
		this.caller = caller;
		this.client = client;
		this.localPort = localPort;
	}

	/**
	 * Run the COMPOUND in args and write COMPOUND4res to out.
	 * @throws XdrException if the arguments before the first operation don't decode (GARBAGE_ARGS)
	 */
	public void run(XdrDecoder args, XdrEncoder reply) throws XdrException {
		this.in = args;
		this.out = reply;
		byte[] tag = in.readOpaque(Nfs4.NFS4_OPAQUE_LIMIT);
		int minor = in.readInt();
		long numops = in.readUnsignedInt();
		int compoundStatus = out.reserve(4);
		out.writeOpaque(tag);
		int countPos = out.reserve(4);
		int count = 0;
		int status = Nfs4.NFS4_OK;
		if( minor != 0 ) {
			status = Nfs4.NFS4ERR_MINOR_VERS_MISMATCH;
		} else if( numops > MAX_OPERATIONS ) {
			status = Nfs4.NFS4ERR_RESOURCE;
		} else {
			for (int i = 0; i < numops; i++) {
				int op;
				try {
					op = in.readInt();
				} catch (XdrException e) {
					status = Nfs4.NFS4ERR_BADXDR;
					break;
				}
				count++;
				status = operation(op);
				if( status != Nfs4.NFS4_OK ) {
					break;
				}
			}
		}
		out.setInt(compoundStatus, status);
		out.setInt(countPos, count);
	}

	/**
	 * @return true if the COMPOUND ran an operation that would answer differently if it ran
	 * again: CREATE, REMOVE, RENAME, LINK or SETATTR
	 */
	public boolean isNonIdempotent() {
		return nonIdempotent;
	}

	private int operation(int op) {
		boolean known = op >= Nfs4.OP_ACCESS && op <= Nfs4.OP_RELEASE_LOCKOWNER;
		switch (op) {
		case Nfs4.OP_CREATE:
		case Nfs4.OP_REMOVE:
		case Nfs4.OP_RENAME:
		case Nfs4.OP_LINK:
		case Nfs4.OP_SETATTR:
			nonIdempotent = true;
			break;
		default:
			break;
		}
		out.writeInt(known ? op : Nfs4.OP_ILLEGAL);
		statusPos = out.reserve(4);
		int status;
		try {
			if( !known ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_OP_ILLEGAL);
			}
			dispatch(op);
			status = Nfs4.NFS4_OK;
		} catch (Replayed r) {
			return r.status;
		} catch (LockDeniedException e) {
			out.truncate(statusPos+4);
			writeDenied(e);
			status = e.getStatus();
		} catch (StateManager.ClientInUseException e) {
			out.truncate(statusPos+4);
			out.writeString(e.netid == null ? "" : e.netid);
			out.writeString(e.addr == null ? "" : e.addr);
			status = e.getStatus();
		} catch (Nfs4Exception e) {
			out.truncate(statusPos+4);
			status = e.getStatus();
			final int st = status;
			server.logDebug(() -> Nfs4.opName(op)+" "+st+(e.getMessage() == null ? "" : " "+e.getMessage()));
		} catch (XdrDecoder.InvalidUtf8Exception e) {
			out.truncate(statusPos+4);
			status = Nfs4.NFS4ERR_INVAL;
		} catch (XdrException e) {
			out.truncate(statusPos+4);
			status = Nfs4.NFS4ERR_BADXDR;
		} catch (IOException e) {
			out.truncate(statusPos+4);
			status = ioStatus(e);
			server.logDebug(() -> Nfs4.opName(op)+" "+e);
		} catch (RuntimeException e) {
			out.truncate(statusPos+4);
			status = Nfs4.NFS4ERR_SERVERFAULT;
			server.logError(Nfs4.opName(op)+" failed", e);
		}
		if( op == Nfs4.OP_SETATTR && status != Nfs4.NFS4_OK ) {
			// SETATTR4res is not a union: attrsset follows any status
			out.writeInt(0);
		}
		out.setInt(statusPos, status);
		return status;
	}

	private static int ioStatus(IOException e) {
		if( e instanceof FileNotFoundException || e instanceof NoSuchFileException ) {
			return Nfs4.NFS4ERR_NOENT;
		}
		if( e instanceof AccessDeniedException ) {
			return Nfs4.NFS4ERR_ACCESS;
		}
		return Nfs4.NFS4ERR_IO;
	}

	private void dispatch(int op) throws Nfs4Exception, IOException {
		switch (op) {
		case Nfs4.OP_ACCESS: access(); break;
		case Nfs4.OP_CLOSE: close(); break;
		case Nfs4.OP_COMMIT: commit(); break;
		case Nfs4.OP_CREATE: create(); break;
		case Nfs4.OP_DELEGPURGE: throw new Nfs4Exception(Nfs4.NFS4ERR_NOTSUPP);
		case Nfs4.OP_DELEGRETURN: delegreturn(); break;
		case Nfs4.OP_GETATTR: getattr(); break;
		case Nfs4.OP_GETFH: getfh(); break;
		case Nfs4.OP_LINK: link(); break;
		case Nfs4.OP_LOCK: lock(); break;
		case Nfs4.OP_LOCKT: lockt(); break;
		case Nfs4.OP_LOCKU: locku(); break;
		case Nfs4.OP_RELEASE_LOCKOWNER: releaseLockOwner(); break;
		case Nfs4.OP_LOOKUP: lookup(); break;
		case Nfs4.OP_LOOKUPP: lookupp(); break;
		case Nfs4.OP_NVERIFY: verify(false); break;
		case Nfs4.OP_OPEN: open(); break;
		case Nfs4.OP_OPENATTR: throw new Nfs4Exception(Nfs4.NFS4ERR_NOTSUPP);
		case Nfs4.OP_OPEN_CONFIRM: openConfirm(); break;
		case Nfs4.OP_OPEN_DOWNGRADE: openDowngrade(); break;
		case Nfs4.OP_PUTFH: putfh(); break;
		case Nfs4.OP_PUTPUBFH:
		case Nfs4.OP_PUTROOTFH: current = server.getRootExport().getRootNode(); break;
		case Nfs4.OP_READ: read(); break;
		case Nfs4.OP_READDIR: readdir(); break;
		case Nfs4.OP_READLINK: readlink(); break;
		case Nfs4.OP_REMOVE: remove(); break;
		case Nfs4.OP_RENAME: rename(); break;
		case Nfs4.OP_RENEW: renew(); break;
		case Nfs4.OP_RESTOREFH: restorefh(); break;
		case Nfs4.OP_SAVEFH: savefh(); break;
		case Nfs4.OP_SECINFO: secinfo(); break;
		case Nfs4.OP_SETATTR: setattr(); break;
		case Nfs4.OP_SETCLIENTID: setclientid(); break;
		case Nfs4.OP_SETCLIENTID_CONFIRM: setclientidConfirm(); break;
		case Nfs4.OP_VERIFY: verify(true); break;
		case Nfs4.OP_WRITE: write(); break;
		default: throw new Nfs4Exception(Nfs4.NFS4ERR_OP_ILLEGAL);
		}
	}

	// ================================================================ helpers

	/** A seqid operation was a retransmission: its saved result was written. */
	private static final class Replayed extends RuntimeException {
		private static final long serialVersionUID = 1L;
		final int status;

		Replayed(int status) {
			super(null, null, false, false);
			this.status = status;
		}
	}

	private interface Body {
		/** @return the file handle to remember with the result (OPEN), or null */
		Node run() throws Nfs4Exception, IOException;
	}

	/**
	 * Run a seqid-sequenced operation of an open-owner (RFC 7530 section 9.1.7): a
	 * retransmission gets the saved result, the next seqid runs and its result is saved.
	 */
	private void sequenced(SeqOwner owner, int seqid, int op, Body body) throws Nfs4Exception, IOException {
		synchronized (owner) {
			if( owner.checkSeqid(seqid, op) ) {
				byte[] saved = owner.getLastResult();
				out.truncate(statusPos);
				out.writeRaw(saved, 0, saved.length);
				if( owner.getLastFh() != null ) {
					current = owner.getLastFh();
				}
				throw new Replayed(java.nio.ByteBuffer.wrap(saved).getInt());
			}
			if( owner instanceof OpenOwner ) {
				server.getStateManager().forgetClosed((OpenOwner) owner);
			}
			try {
				Node fh = body.run();
				byte[] result = Arrays.copyOfRange(out.array(), statusPos, out.size());
				// the status is set after this returns
				result[0] = result[1] = result[2] = result[3] = 0;
				owner.done(seqid, op, result, fh);
			} catch (Nfs4Exception e) {
				if( advancesSeqid(e.getStatus()) ) {
					XdrEncoder saved = new XdrEncoder(64);
					saved.writeInt(e.getStatus());
					if( e instanceof LockDeniedException ) {
						// a retransmitted LOCK is denied with the same LOCK4denied
						writeDenied(saved, (LockDeniedException) e);
					}
					owner.done(seqid, op, saved.toByteArray(), null);
				}
				throw e;
			}
		}
	}

	/** RFC 7530 section 9.1.7: these errors don't count as the owner's next seqid. */
	private static boolean advancesSeqid(int status) {
		switch (status) {
		case Nfs4.NFS4ERR_STALE_CLIENTID:
		case Nfs4.NFS4ERR_STALE_STATEID:
		case Nfs4.NFS4ERR_BAD_STATEID:
		case Nfs4.NFS4ERR_BAD_SEQID:
		case Nfs4.NFS4ERR_BADXDR:
		case Nfs4.NFS4ERR_RESOURCE:
		case Nfs4.NFS4ERR_NOFILEHANDLE:
		case Nfs4.NFS4ERR_MOVED:
			return false;
		default:
			return true;
		}
	}

	private Node current() throws Nfs4Exception {
		if( current == null ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_NOFILEHANDLE);
		}
		return current;
	}

	/** @return the current file, which must still exist (else NFS4ERR_STALE) */
	private FileSource currentFile() throws Nfs4Exception, IOException {
		return existing(current());
	}

	private FileSource existing(Node node) throws Nfs4Exception, IOException {
		FileSource f = node.getExport().resolve(node);
		if( !f.exists() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_STALE);
		}
		return f;
	}

	/** @return the current folder; NFS4ERR_NOTDIR if the current file isn't one */
	private FileSource currentDir() throws Nfs4Exception, IOException {
		FileSource f = currentFile();
		if( !f.isDirectory() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_NOTDIR);
		}
		return f;
	}

	/** @return a regular file; NFS4ERR_ISDIR for a folder */
	private FileSource currentRegular() throws Nfs4Exception, IOException {
		FileSource f = currentFile();
		if( f.isDirectory() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_ISDIR);
		}
		return f;
	}

	/** @return the export (or the folder of exports) the current file is in */
	private Export exp() throws Nfs4Exception {
		return current().getExport();
	}

	private void allowRead() throws Nfs4Exception {
		server.getAuthorizer().check(exp(), caller, client, localPort, false);
	}

	private void allowWrite() throws Nfs4Exception {
		Export export = exp();
		if( export.isReadOnly() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_ROFS);
		}
		server.getAuthorizer().check(export, caller, client, localPort, true);
	}

	private String component() throws XdrException, Nfs4Exception {
		String name = in.readString(Nfs4.NFS4_OPAQUE_LIMIT);
		Export.checkName(name);
		return name;
	}

	private StateId stateid() throws XdrException {
		int seqid = in.readInt();
		byte[] other = in.readFixedOpaque(Nfs4.NFS4_OTHER_SIZE);
		return new StateId(seqid, other);
	}

	private void writeStateid(StateId id) {
		out.writeInt(id.seqid);
		out.writeFixedOpaque(id.other);
	}

	/** The change attribute of a folder (as {@link Attributes} makes it). */
	private static long change(Node node, FileSource f) throws IOException {
		return f.lastModified()*1000+(node.getChanges() % 1000);
	}

	/** change_info4: not atomic (another process may change the folder at the same time) */
	private void changeInfo(long before, long after) {
		out.writeBoolean(false);
		out.writeLong(before);
		out.writeLong(after);
	}

	/**
	 * Check a stateid for READ, WRITE or SETATTR of the size.
	 * @param access OPEN4_SHARE_ACCESS_READ or _WRITE
	 */
	private void checkIo(StateId id, Node node, int access) throws Nfs4Exception {
		if( id.isBypass() && access == Nfs4.OPEN4_SHARE_ACCESS_READ ) {
			return;
		}
		if( id.isSpecial() ) {
			state.checkAnonymous(node, access);
			return;
		}
		OpenState s = state.findIo(id, node);
		if( !s.getOwner().isConfirmed() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID, "open not confirmed");
		}
		if( access == Nfs4.OPEN4_SHARE_ACCESS_WRITE && (s.getAccess() & Nfs4.OPEN4_SHARE_ACCESS_WRITE) == 0 ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_OPENMODE);
		}
	}

	// ================================================================ file handles

	private void putfh() throws Nfs4Exception, XdrException {
		byte[] fh = in.readOpaque(Nfs4.NFS4_FHSIZE);
		current = server.fromHandle(fh);
	}

	private void getfh() throws Nfs4Exception {
		out.writeOpaque(current().getExport().toHandle(current()));
	}

	private void savefh() throws Nfs4Exception {
		saved = current();
	}

	private void restorefh() throws Nfs4Exception {
		if( saved == null ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_RESTOREFH);
		}
		current = saved;
	}

	private void lookup() throws Nfs4Exception, IOException {
		String name = component();
		Node dir = current();
		currentDir();
		Export export = dir.getExport();
		if( export.isPseudo() ) {
			// into an export (decision D27); one the caller may not read is NFS4ERR_ACCESS
			Export target = server.findExport(name);
			if( target == null ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_NOENT);
			}
			server.getAuthorizer().check(target, caller, client, localPort, false);
			current = target.getRootNode();
			return;
		}
		allowRead();
		String path = Export.childPath(dir, name);
		if( !export.resolve(path).exists() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_NOENT);
		}
		current = export.getNodes().get(path);
	}

	private void lookupp() throws Nfs4Exception, IOException {
		Node node = current();
		currentDir();
		allowRead();
		if( node.isRoot() ) {
			// the root of an export is in the folder of exports, if there is one (decision D27)
			Export pseudo = server.getPseudoRoot();
			if( pseudo == null || node.getExport().isPseudo() ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_NOENT);
			}
			current = pseudo.getRootNode();
			return;
		}
		current = node.getExport().getNodes().get(node.getParentPath());
	}

	private void secinfo() throws Nfs4Exception, IOException {
		String name = component();
		Node dir = current();
		Export export = dir.getExport();
		currentDir();
		if( !export.resolve(Export.childPath(dir, name)).exists() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_NOENT);
		}
		// the flavors the server takes, strongest first (decision D37)
		boolean none = server.isAuthNoneAllowed();
		boolean sys = server.allowsSec(NfsServer.SEC_SYS);
		int[] services = { GssServer.SVC_PRIVACY, GssServer.SVC_INTEGRITY, GssServer.SVC_NONE };
		String[] names = { NfsServer.SEC_KRB5P, NfsServer.SEC_KRB5I, NfsServer.SEC_KRB5 };
		List<Integer> gss = new ArrayList<Integer>();
		for (int i = 0; i < names.length; i++) {
			if( server.allowsSec(names[i]) ) {
				gss.add(services[i]);
			}
		}
		out.writeInt(gss.size()+(sys ? 1 : 0)+(none ? 1 : 0));
		for (int service : gss) {
			out.writeInt(Rpc.RPCSEC_GSS);
			// the OID is DER: tag 6, its length, then the bytes (RFC 7530 section 3.2.1 sec_oid4)
			byte[] oid = new byte[GssServer.KRB5_OID.length+2];
			oid[0] = 6;
			oid[1] = (byte) GssServer.KRB5_OID.length;
			System.arraycopy(GssServer.KRB5_OID, 0, oid, 2, GssServer.KRB5_OID.length);
			out.writeOpaque(oid);
			out.writeInt(0); // GSS_C_QOP_DEFAULT
			out.writeInt(service);
		}
		if( sys ) {
			out.writeInt(Rpc.AUTH_SYS);
		}
		if( none ) {
			out.writeInt(Rpc.AUTH_NONE);
		}
		// RFC 7530 section 16.31.4: SECINFO consumes the current file handle
		current = null;
	}

	// ================================================================ attributes

	private Attributes.Source source(Node node, FileSource f) throws IOException {
		return new Attributes.Source(node.getExport(), node, f, f.isDirectory() ? null : files.length(node));
	}

	private void getattr() throws Nfs4Exception, IOException {
		int[] request = in.readIntArray(Attributes.MAX_BITMAP_WORDS);
		FileSource f = currentFile();
		allowRead();
		if( Attributes.hasWriteOnly(request) ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "write-only attribute asked for");
		}
		attributes.encode(source(current, f), request, out);
	}

	private void verify(boolean same) throws Nfs4Exception, IOException {
		int[] bits = in.readIntArray(Attributes.MAX_BITMAP_WORDS);
		byte[] theirs = in.readOpaque(64*1024);
		FileSource f = currentFile();
		allowRead();
		if( Attributes.hasOtherThan(bits, Attributes.SUPPORTED) ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_ATTRNOTSUPP);
		}
		if( Attributes.isSet(bits, Nfs4.FATTR4_RDATTR_ERROR) || Attributes.isSet(bits, Nfs4.FATTR4_TIME_ACCESS_SET)
				|| Attributes.isSet(bits, Nfs4.FATTR4_TIME_MODIFY_SET) ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL);
		}
		XdrEncoder mine = new XdrEncoder(256);
		attributes.encode(source(current, f), bits, mine);
		XdrDecoder d = new XdrDecoder(mine.toByteArray());
		d.readIntArray(8);
		byte[] values = d.readOpaque(64*1024);
		boolean equal = Arrays.equals(values, theirs);
		if( same && !equal ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_NOT_SAME);
		}
		if( !same && equal ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_SAME);
		}
	}

	private void setattr() throws Nfs4Exception, IOException {
		StateId id = stateid();
		Attributes.Settings s = Attributes.decodeSettings(in);
		Node node = current();
		FileSource f = currentFile();
		allowWrite();
		if( s.size != null ) {
			if( f.isDirectory() ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_ISDIR);
			}
			checkIo(id, node, Nfs4.OPEN4_SHARE_ACCESS_WRITE);
		}
		attributes.apply(s, f);
		if( s.size != null ) {
			files.setLength(node, f, s.size);
		}
		node.changed();
		out.writeIntArray(s.bits);
	}

	private void access() throws Nfs4Exception, IOException {
		int asked = in.readInt();
		FileSource f = currentFile();
		boolean dir = f.isDirectory();
		// RFC 7530 section 16.1.4: LOOKUP and DELETE mean something only for a folder, EXECUTE only for a file
		int meaningful = Nfs4.ACCESS4_READ | Nfs4.ACCESS4_MODIFY | Nfs4.ACCESS4_EXTEND
				| (dir ? Nfs4.ACCESS4_LOOKUP | Nfs4.ACCESS4_DELETE : Nfs4.ACCESS4_EXECUTE);
		int supported = asked & meaningful;
		int allowed = 0;
		if( permitted(false) ) {
			allowed |= Nfs4.ACCESS4_READ;
			if( dir ) {
				allowed |= Nfs4.ACCESS4_LOOKUP;
			} else if( f.canOwnerExecute() || f.canGroupExecute() || f.canOtherExecute() ) {
				allowed |= Nfs4.ACCESS4_EXECUTE;
			}
		}
		if( permitted(true) && (dir || f.canWrite()) ) {
			allowed |= Nfs4.ACCESS4_MODIFY | Nfs4.ACCESS4_EXTEND | Nfs4.ACCESS4_DELETE;
		}
		out.writeInt(supported);
		out.writeInt(supported & allowed);
	}

	/** @return true if the caller may read the export; NFS4ERR_DELAY (registry unavailable) is thrown */
	private boolean mayRead(Export target) throws Nfs4Exception {
		try {
			server.getAuthorizer().check(target, caller, client, localPort, false);
			return true;
		} catch (Nfs4Exception e) {
			if( e.getStatus() == Nfs4.NFS4ERR_DELAY ) {
				throw e;
			}
			return false;
		}
	}

	/** @return true if allowed; NFS4ERR_DELAY (registry unavailable) still fails the ACCESS */
	private boolean permitted(boolean write) throws Nfs4Exception {
		try {
			if( write ) {
				allowWrite();
			} else {
				allowRead();
			}
			return true;
		} catch (Nfs4Exception e) {
			if( e.getStatus() == Nfs4.NFS4ERR_DELAY ) {
				throw e;
			}
			return false;
		}
	}

	// ================================================================ folders

	private void readdir() throws Nfs4Exception, IOException {
		long cookie = in.readLong();
		in.readFixedOpaque(Nfs4.NFS4_VERIFIER_SIZE);
		long dircount = in.readUnsignedInt();
		long maxcount = in.readUnsignedInt();
		int[] request = in.readIntArray(Attributes.MAX_BITMAP_WORDS);
		Node dir = current();
		Export export = dir.getExport();
		FileSource d = currentDir();
		allowRead();
		if( cookie == 1 || cookie == 2 ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_COOKIE);
		}
		if( Attributes.hasWriteOnly(request) ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "write-only attribute asked for");
		}
		if( maxcount < READDIR_EMPTY ) {
			// not even room for the verifier and an empty list
			throw new Nfs4Exception(Nfs4.NFS4ERR_TOOSMALL);
		}
		String[] names = d.list();
		if( names == null ) {
			names = new String[0];
		}
		if( export.isPseudo() && server.isHidingUnreadableExports() ) {
			// only the exports the caller may read (decision D30)
			List<String> shown = new ArrayList<String>();
			for (String n : names) {
				Export target = server.findExport(n);
				if( target != null && mayRead(target) ) {
					shown.add(n);
				}
			}
			names = shown.toArray(new String[0]);
		}
		Arrays.sort(names);
		long first = cookie == 0 ? 0 : cookie-2;
		if( first > names.length ) {
			// the folder has fewer entries than when the cookie was given: nothing more
			first = names.length;
		}
		// the verifier stays the same: cookies are positions in the sorted names (decision D10)
		int start = out.size();
		out.writeFixedOpaque(new byte[Nfs4.NFS4_VERIFIER_SIZE]);
		boolean rdattrError = Attributes.isSet(request, Nfs4.FATTR4_RDATTR_ERROR);
		long limit = Math.min(maxcount, server.getMaxRead()+4096);
		long names2 = 0;
		int entries = 0;
		boolean eof = true;
		for (int i = (int) first; i < names.length; i++) {
			String name = names[i];
			int mark = out.size();
			out.writeBoolean(true);
			out.writeLong(i+3);
			out.writeString(name);
			String path = Export.childPath(dir, name);
			Export target = export.isPseudo() ? server.findExport(name) : null;
			// in the folder of exports an entry is the export's own root, with its own fsid
			Node node = target != null ? target.getRootNode() : export.getNodes().get(path);
			try {
				FileSource f = target != null ? target.getRoot() : export.resolve(path);
				attributes.encode(source(node, f), request, out);
			} catch (IOException e) {
				if( !rdattrError ) {
					throw e;
				}
				out.truncate(mark);
				out.writeBoolean(true);
				out.writeLong(i+3);
				out.writeString(name);
				Attributes.encodeError(ioStatus(e), out);
			}
			names2 += name.length()+16;
			// room for the end of the list and eof
			if( out.size()-start+8 > limit || (entries > 0 && dircount > 0 && names2 > dircount) ) {
				out.truncate(mark);
				eof = false;
				break;
			}
			entries++;
		}
		if( entries == 0 && !eof ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_TOOSMALL);
		}
		out.writeBoolean(false);
		out.writeBoolean(eof);
	}

	private void readlink() throws Nfs4Exception, IOException {
		currentFile();
		// symbolic links are followed (symlink_support is false): nothing is a link
		throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "not a symbolic link");
	}

	private void create() throws Nfs4Exception, IOException {
		int type = in.readInt();
		switch (type) {
		case Nfs4.NF4LNK:
			in.readOpaque(Nfs4.NFS4_OPAQUE_LIMIT*4);
			break;
		case Nfs4.NF4BLK:
		case Nfs4.NF4CHR:
			in.readInt();
			in.readInt();
			break;
		default:
			break;
		}
		String name = component();
		Attributes.Settings settings = Attributes.decodeSettings(in);
		Node dir = current();
		Export export = dir.getExport();
		FileSource d = currentDir();
		if( type == Nfs4.NF4REG ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BADTYPE);
		}
		if( type != Nfs4.NF4DIR ) {
			// symbolic links, devices, sockets and fifos aren't served (decisions D24, D26):
			// PERM, which a Linux client shows as "Operation not permitted" (NOTSUPP and BADTYPE are
			// not Linux error numbers a program can name: "Unknown error 524")
			throw new Nfs4Exception(Nfs4.NFS4ERR_PERM, "can't create type "+type);
		}
		allowWrite();
		String path = Export.childPath(dir, name);
		FileSource f = export.resolve(path);
		long before = change(dir, d);
		if( f.exists() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_EXIST);
		}
		if( !f.mkdir() ) {
			if( f.exists() ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_EXIST);
			}
			throw new Nfs4Exception(Nfs4.NFS4ERR_IO, "mkdir "+path+" failed");
		}
		dir.changed();
		Node node = export.getNodes().get(path);
		int[] set = applyCreated(settings, null, f);
		changeInfo(before, change(dir, d));
		out.writeIntArray(set);
		current = node;
	}

	/**
	 * Attributes given when a file is made: what can't be set is left out of attrset.
	 *
	 * @param node the new regular file, whose size is set through its open stream; null for a
	 * folder, which has no size to set
	 */
	private int[] applyCreated(Attributes.Settings s, Node node, FileSource f) {
		if( s == null || s.isEmpty() ) {
			return new int[0];
		}
		Attributes.Settings rest = new Attributes.Settings();
		rest.mode = s.mode;
		rest.atime = s.atime;
		rest.mtime = s.mtime;
		int[] ret = s.bits.clone();
		// owner and group are the server's
		clear(ret, Nfs4.FATTR4_OWNER);
		clear(ret, Nfs4.FATTR4_OWNER_GROUP);
		if( s.size != null ) {
			if( node == null ) {
				clear(ret, Nfs4.FATTR4_SIZE);
			} else if( s.size != 0 ) {
				// a new file is empty: only another size needs setting
				try {
					files.setLength(node, f, s.size);
				} catch (IOException | Nfs4Exception e) {
					server.logDebug(() -> "size of new "+f.getAbsolutePath()+" not set: "+e.getMessage());
					clear(ret, Nfs4.FATTR4_SIZE);
				}
			}
		}
		try {
			attributes.apply(rest, f);
			return ret;
		} catch (IOException | Nfs4Exception e) {
			server.logDebug(() -> "attributes of new "+f.getAbsolutePath()+" not set: "+e.getMessage());
			return Attributes.isSet(ret, Nfs4.FATTR4_SIZE) ? Attributes.bitmap(Nfs4.FATTR4_SIZE) : new int[0];
		}
	}

	private static void clear(int[] bitmap, int bit) {
		if( bit/32 < bitmap.length ) {
			bitmap[bit/32] &= ~(1 << (bit%32));
		}
	}

	private void remove() throws Nfs4Exception, IOException {
		String name = component();
		Node dir = current();
		Export export = dir.getExport();
		FileSource d = currentDir();
		allowWrite();
		String path = Export.childPath(dir, name);
		FileSource f = export.resolve(path);
		if( !f.exists() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_NOENT);
		}
		if( f.isDirectory() ) {
			String[] list = f.list();
			if( list != null && list.length > 0 ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_NOTEMPTY);
			}
		}
		long before = change(dir, d);
		Node node = export.getNodes().findPath(path);
		if( node != null ) {
			files.release(node);
		}
		if( !f.delete() ) {
			throw new Nfs4Exception(f.exists() ? Nfs4.NFS4ERR_IO : Nfs4.NFS4ERR_NOENT, "delete "+path+" failed");
		}
		export.getNodes().removed(path);
		dir.changed();
		changeInfo(before, change(dir, d));
	}

	private void rename() throws Nfs4Exception, IOException {
		String oldName = component();
		String newName = component();
		if( saved == null ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_NOFILEHANDLE);
		}
		Node fromDir = saved;
		Node toDir = current();
		Export export = toDir.getExport();
		if( fromDir.getExport() != toDir.getExport() ) {
			// separate file systems (decision D31)
			throw new Nfs4Exception(Nfs4.NFS4ERR_XDEV, "from one export to another");
		}
		FileSource fd = existing(fromDir);
		FileSource td = currentDir();
		if( !fd.isDirectory() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_NOTDIR);
		}
		allowWrite();
		String fromPath = Export.childPath(fromDir, oldName);
		String toPath = Export.childPath(toDir, newName);
		FileSource from = export.resolve(fromPath);
		FileSource to = export.resolve(toPath);
		if( !from.exists() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_NOENT);
		}
		if( toPath.equals(fromPath) || toPath.startsWith(fromPath+"/") ) {
			if( toPath.equals(fromPath) ) {
				long c = change(fromDir, fd);
				changeInfo(c, c);
				changeInfo(c, c);
				return;
			}
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "can't move a folder into itself");
		}
		long fromBefore = change(fromDir, fd);
		long toBefore = change(toDir, td);
		boolean fromIsDir = from.isDirectory();
		if( to.exists() ) {
			boolean toIsDir = to.isDirectory();
			if( fromIsDir != toIsDir ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_EXIST);
			}
			if( toIsDir ) {
				String[] list = to.list();
				if( list != null && list.length > 0 ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_EXIST);
				}
				if( !to.delete() ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_IO, "delete "+toPath+" failed");
				}
			}
		}
		Node fromNode = export.getNodes().findPath(fromPath);
		if( fromNode != null ) {
			files.release(fromNode);
		}
		Node toNode = export.getNodes().findPath(toPath);
		if( toNode != null ) {
			files.release(toNode);
		}
		boolean ok = to.exists() ? from.renameTo(to, true) : from.renameTo(to);
		if( !ok ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_IO, "rename "+fromPath+" to "+toPath+" failed");
		}
		export.getNodes().renamed(fromPath, toPath);
		fromDir.changed();
		toDir.changed();
		changeInfo(fromBefore, change(fromDir, fd));
		changeInfo(toBefore, change(toDir, td));
	}

	private void link() throws Nfs4Exception, XdrException {
		component();
		// hard links aren't served: a FileSource has no file identity or link count (decisions D24, D25): XDEV, which a Linux
		// client shows as "Invalid cross-device link" (NOTSUPP is "Unknown error 524")
		throw new Nfs4Exception(Nfs4.NFS4ERR_XDEV, "no hard links in this export");
	}

	// ================================================================ data

	private void read() throws Nfs4Exception, IOException {
		StateId id = stateid();
		long offset = in.readLong();
		long count = in.readUnsignedInt();
		Node node = current();
		FileSource f = currentRegular();
		allowRead();
		checkIo(id, node, Nfs4.OPEN4_SHARE_ACCESS_READ);
		if( offset < 0 ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL);
		}
		int n = (int) Math.min(count, server.getMaxRead());
		int eofPos = out.reserve(4);
		int lenPos = out.reserve(4);
		int dataPos = out.reserve(XdrDecoder.pad(n));
		int got = files.read(node, f, offset, out.array(), dataPos, n);
		out.truncate(dataPos+XdrDecoder.pad(got));
		java.util.Arrays.fill(out.array(), dataPos+got, dataPos+XdrDecoder.pad(got), (byte) 0);
		Long len = files.length(node);
		long size = len != null ? len : f.length();
		out.setInt(eofPos, offset+got >= size ? 1 : 0);
		out.setInt(lenPos, got);
	}

	private void write() throws Nfs4Exception, IOException {
		StateId id = stateid();
		long offset = in.readLong();
		int stable = in.readInt();
		int len = (int) in.readUnsignedInt();
		if( len < 0 || len > in.remaining() ) {
			throw new XdrException("WRITE data longer than the call");
		}
		int dataPos = in.position();
		in.skip(len);
		Node node = current();
		FileSource f = currentRegular();
		allowWrite();
		checkIo(id, node, Nfs4.OPEN4_SHARE_ACCESS_WRITE);
		if( offset < 0 || offset+len < 0 ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_FBIG);
		}
		boolean sync = stable != Nfs4.UNSTABLE4;
		java.nio.ByteBuffer b = in.buffer();
		byte[] data;
		int off;
		if( b.hasArray() ) {
			data = b.array();
			off = b.arrayOffset()+dataPos;
		} else {
			data = new byte[len];
			b.duplicate().position(dataPos).get(data);
			off = 0;
		}
		files.write(node, f, offset, data, off, len, sync);
		node.changed();
		out.writeInt(len);
		out.writeInt(sync ? Nfs4.FILE_SYNC4 : Nfs4.UNSTABLE4);
		out.writeFixedOpaque(server.getWriteVerifier());
	}

	private void commit() throws Nfs4Exception, IOException {
		long offset = in.readLong();
		long count = in.readUnsignedInt();
		Node node = current();
		currentRegular();
		if( Long.compareUnsigned(offset+count, offset) < 0 ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "offset + count is past 2^64");
		}
		files.commit(node);
		out.writeFixedOpaque(server.getWriteVerifier());
	}

	// ================================================================ client IDs

	private void setclientid() throws Nfs4Exception, XdrException {
		byte[] verifier = in.readFixedOpaque(Nfs4.NFS4_VERIFIER_SIZE);
		byte[] id = in.readOpaque(Nfs4.NFS4_OPAQUE_LIMIT);
		in.readUnsignedInt(); // cb_program: no callbacks (no delegations)
		String netid = in.readString(Nfs4.NFS4_OPAQUE_LIMIT);
		String addr = in.readString(Nfs4.NFS4_OPAQUE_LIMIT);
		in.readUnsignedInt(); // callback_ident
		ClientRecord r = state.setClientId(verifier, id, caller.getStateKey(), netid, addr);
		out.writeLong(r.getClientid());
		out.writeFixedOpaque(r.getConfirmVerifier());
	}

	private void setclientidConfirm() throws Nfs4Exception, XdrException {
		long clientid = in.readLong();
		byte[] verifier = in.readFixedOpaque(Nfs4.NFS4_VERIFIER_SIZE);
		state.confirm(clientid, verifier, caller.getStateKey());
	}

	private void renew() throws Nfs4Exception, XdrException {
		long clientid = in.readLong();
		state.renew(clientid);
	}

	private void delegreturn() throws Nfs4Exception, XdrException {
		stateid();
		current();
		// no delegations are handed out
		throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID);
	}

	// ================================================================ opens

	private void open() throws Nfs4Exception, IOException {
		final int seqid = in.readInt();
		final int access = in.readInt();
		final int deny = in.readInt();
		long clientid = in.readLong();
		byte[] ownerBytes = in.readOpaque(Nfs4.NFS4_OPAQUE_LIMIT);
		final int opentype = in.readInt();
		int how = -1;
		Attributes.Settings settings = null;
		byte[] exclusive = null;
		if( opentype == Nfs4.OPEN4_CREATE ) {
			how = in.readInt();
			if( how == Nfs4.UNCHECKED4 || how == Nfs4.GUARDED4 ) {
				settings = Attributes.decodeSettings(in);
			} else if( how == Nfs4.EXCLUSIVE4 ) {
				exclusive = in.readFixedOpaque(Nfs4.NFS4_VERIFIER_SIZE);
			} else {
				throw new XdrException("createmode4 "+how);
			}
		} else if( opentype != Nfs4.OPEN4_NOCREATE ) {
			throw new XdrException("opentype4 "+opentype);
		}
		int claim = in.readInt();
		String name = null;
		switch (claim) {
		case Nfs4.CLAIM_NULL:
			name = in.readString(Nfs4.NFS4_OPAQUE_LIMIT);
			break;
		case Nfs4.CLAIM_PREVIOUS:
			in.readInt();
			break;
		case Nfs4.CLAIM_DELEGATE_CUR:
			stateid();
			in.readString(Nfs4.NFS4_OPAQUE_LIMIT);
			break;
		case Nfs4.CLAIM_DELEGATE_PREV:
			in.readString(Nfs4.NFS4_OPAQUE_LIMIT);
			break;
		default:
			throw new XdrException("open_claim_type4 "+claim);
		}
		if( access < Nfs4.OPEN4_SHARE_ACCESS_READ || access > Nfs4.OPEN4_SHARE_ACCESS_BOTH
				|| deny < 0 || deny > Nfs4.OPEN4_SHARE_DENY_BOTH ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "share access "+access+" deny "+deny);
		}
		Node dir = current();
		ClientRecord c = state.renew(clientid);
		OpenOwner owner = state.openOwner(c, ownerBytes, seqid);
		final String fname = name;
		final int fhow = how;
		final Attributes.Settings fsettings = settings;
		final byte[] fexclusive = exclusive;
		sequenced(owner, seqid, Nfs4.OP_OPEN, () -> {
			if( claim == Nfs4.CLAIM_PREVIOUS ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_NO_GRACE);
			}
			if( claim != Nfs4.CLAIM_NULL ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_NOTSUPP, "no delegations");
			}
			Export.checkName(fname);
			return openNull(owner, dir, fname, access, deny, opentype, fhow, fsettings, fexclusive);
		});
	}

	private Node openNull(OpenOwner owner, Node dir, String name, int access, int deny, int opentype, int how,
			Attributes.Settings settings, byte[] exclusive) throws Nfs4Exception, IOException {
		FileSource d = currentDir();
		Export export = dir.getExport();
		if( (access & Nfs4.OPEN4_SHARE_ACCESS_READ) != 0 ) {
			allowRead();
		}
		if( (access & Nfs4.OPEN4_SHARE_ACCESS_WRITE) != 0 || opentype == Nfs4.OPEN4_CREATE ) {
			allowWrite();
		}
		String path = Export.childPath(dir, name);
		FileSource f = export.resolve(path);
		long before = change(dir, d);
		int[] attrset = new int[0];
		boolean truncate = false;
		if( opentype == Nfs4.OPEN4_CREATE ) {
			boolean exists = f.exists();
			if( how == Nfs4.EXCLUSIVE4 ) {
				if( exists ) {
					if( !server.isExclusiveCreate(path, exclusive) ) {
						throw new Nfs4Exception(Nfs4.NFS4ERR_EXIST);
					}
				} else {
					create(f, path);
					server.setExclusiveCreate(path, exclusive);
					dir.changed();
				}
			} else {
				if( exists && how == Nfs4.GUARDED4 ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_EXIST);
				}
				if( !exists ) {
					create(f, path);
					dir.changed();
					Node made = export.getNodes().get(path);
					if( (access & Nfs4.OPEN4_SHARE_ACCESS_WRITE) != 0 ) {
						// before a mode without write is set (D16)
						openForWrite(made, f);
					}
					attrset = applyCreated(settings, made, f);
				} else if( settings != null && settings.size != null && settings.size == 0 ) {
					// an existing file: only a size of 0 (truncate) counts, the other attributes
					// are ignored (RFC 7530 section 16.16.5)
					truncate = true;
					attrset = Attributes.bitmap(Nfs4.FATTR4_SIZE);
				}
			}
		} else if( !f.exists() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_NOENT);
		}
		if( f.isDirectory() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_ISDIR);
		}
		Node node = export.getNodes().get(path);
		OpenState s = state.open(owner, node, access, deny);
		if( (access & Nfs4.OPEN4_SHARE_ACCESS_WRITE) != 0 ) {
			// a SETATTR of a mode without write after this OPEN doesn't stop its WRITEs (D16)
			openForWrite(node, f);
		}
		if( truncate ) {
			try {
				files.setLength(node, f, 0);
				node.changed();
			} catch (IOException | Nfs4Exception e) {
				state.close(s);
				throw e;
			}
		}
		writeStateid(s.stateId());
		changeInfo(before, change(dir, d));
		int rflags = Nfs4.OPEN4_RESULT_LOCKTYPE_POSIX | (owner.isConfirmed() ? 0 : Nfs4.OPEN4_RESULT_CONFIRM);
		out.writeInt(rflags);
		out.writeIntArray(attrset);
		out.writeInt(Nfs4.OPEN_DELEGATE_NONE);
		current = node;
		return node;
	}

	private void openForWrite(Node node, FileSource f) {
		try {
			files.openForWrite(node, f);
		} catch (IOException e) {
			// WRITE opens it again and reports the error
			server.logDebug(() -> f.getAbsolutePath()+" not opened for writing: "+e.getMessage());
		}
	}

	private static void create(FileSource f, String path) throws IOException, Nfs4Exception {
		if( !f.createNewFile() && !f.exists() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_IO, "create "+path+" failed");
		}
	}

	/** The open a stateid names, without its seqid checked yet (a retransmission has an old one). */
	private OpenState openOf(StateId id, Node node) throws Nfs4Exception {
		if( id.isSpecial() ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID);
		}
		try {
			return state.find(id, node, true, true);
		} catch (Nfs4Exception e) {
			if( e.getStatus() != Nfs4.NFS4ERR_OLD_STATEID ) {
				throw e;
			}
			return state.find(new StateId(id.seqid+1, id.other), node, true, true);
		}
	}

	private void openConfirm() throws Nfs4Exception, IOException {
		StateId id = stateid();
		int seqid = in.readInt();
		Node node = current();
		OpenState s = openOf(id, node);
		OpenOwner owner = s.getOwner();
		sequenced(owner, seqid, Nfs4.OP_OPEN_CONFIRM, () -> {
			state.find(id, node, true);
			if( owner.isConfirmed() ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID, "already confirmed");
			}
			state.confirmOwner(s);
			writeStateid(s.stateId());
			return null;
		});
	}

	private void openDowngrade() throws Nfs4Exception, IOException {
		StateId id = stateid();
		int seqid = in.readInt();
		int access = in.readInt();
		int deny = in.readInt();
		Node node = current();
		OpenState s = openOf(id, node);
		OpenOwner owner = s.getOwner();
		sequenced(owner, seqid, Nfs4.OP_OPEN_DOWNGRADE, () -> {
			state.find(id, node, true);
			if( !owner.isConfirmed() ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID, "open not confirmed");
			}
			state.downgrade(s, access, deny);
			writeStateid(s.stateId());
			return null;
		});
	}

	private void writeDenied(LockDeniedException e) {
		writeDenied(out, e);
	}

	/** LOCK4denied */
	private static void writeDenied(XdrEncoder to, LockDeniedException e) {
		to.writeLong(e.offset);
		to.writeLong(e.length);
		to.writeInt(e.write ? Nfs4.WRITE_LT : Nfs4.READ_LT);
		to.writeLong(e.clientid);
		to.writeOpaque(e.owner);
	}

	/** @return true for a write lock type; NFS4ERR_INVAL for no lock type */
	private static boolean isWrite(int locktype) throws Nfs4Exception {
		switch (locktype) {
		case Nfs4.READ_LT:
		case Nfs4.READW_LT:
			return false;
		case Nfs4.WRITE_LT:
		case Nfs4.WRITEW_LT:
			return true;
		default:
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "lock type "+locktype);
		}
	}

	/**
	 * LOCK (decisions D17 to D21): a new lock-owner's first LOCK is a seqid operation of the
	 * open-owner (open_to_lock_owner4), later ones of the lock-owner. A lock is taken at once or
	 * denied (READW_LT and WRITEW_LT too, D19).
	 */
	private void lock() throws Nfs4Exception, IOException {
		int locktype = in.readInt();
		boolean reclaim = in.readBoolean();
		long offset = in.readLong();
		long length = in.readLong();
		Node node = current();
		if( in.readBoolean() ) {
			int openSeqid = in.readInt();
			StateId openId = stateid();
			int lockSeqid = in.readInt();
			long clientid = in.readLong();
			byte[] owner = in.readOpaque(Nfs4.NFS4_OPAQUE_LIMIT);
			OpenState open = openOf(openId, node);
			sequenced(open.getOwner(), openSeqid, Nfs4.OP_LOCK, () -> {
				state.find(openId, node, true);
				if( !open.getOwner().isConfirmed() ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID, "open not confirmed");
				}
				if( clientid != open.getOwner().getClient().getClientid() ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID, "lock-owner of another client");
				}
				LockOwner lo = state.lockOwner(open.getOwner().getClient(), owner, lockSeqid);
				synchronized (lo) {
					LockState l = state.lockState(lo, open);
					try {
						takeLock(l, locktype, reclaim, offset, length);
					} catch (Nfs4Exception e) {
						state.forgetIfUnused(l);
						throw e;
					} finally {
						// the lock-owner's next seqid is lock_seqid+1 either way
						lo.done(lockSeqid, Nfs4.OP_LOCK, null, null);
					}
				}
				return null;
			});
		} else {
			StateId lockId = stateid();
			int lockSeqid = in.readInt();
			LockState l = state.findLock(lockId, node, false);
			sequenced(l.getOwner(), lockSeqid, Nfs4.OP_LOCK, () -> {
				state.findLock(lockId, node, true);
				takeLock(l, locktype, reclaim, offset, length);
				return null;
			});
		}
	}

	private void takeLock(LockState l, int locktype, boolean reclaim, long offset, long length)
			throws Nfs4Exception, IOException {
		boolean write = isWrite(locktype);
		currentRegular();
		if( reclaim ) {
			// no grace period (D21)
			throw new Nfs4Exception(Nfs4.NFS4ERR_NO_GRACE);
		}
		int need = write ? Nfs4.OPEN4_SHARE_ACCESS_WRITE : Nfs4.OPEN4_SHARE_ACCESS_READ;
		if( (l.getOpen().getAccess() & need) == 0 ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_OPENMODE, "lock type needs the open's "+(write ? "write" : "read")+" access");
		}
		state.lock(l, write, offset, StateManager.end(offset, length));
		writeStateid(l.stateId());
	}

	private void lockt() throws Nfs4Exception, IOException {
		int locktype = in.readInt();
		long offset = in.readLong();
		long length = in.readLong();
		long clientid = in.readLong();
		byte[] owner = in.readOpaque(Nfs4.NFS4_OPAQUE_LIMIT);
		Node node = current();
		boolean write = isWrite(locktype);
		currentRegular();
		ClientRecord c = state.renew(clientid);
		LockDeniedException denied = state.test(node, c, owner, write, offset, StateManager.end(offset, length));
		if( denied != null ) {
			throw denied;
		}
	}

	private void locku() throws Nfs4Exception, IOException {
		int locktype = in.readInt();
		int seqid = in.readInt();
		StateId lockId = stateid();
		long offset = in.readLong();
		long length = in.readLong();
		Node node = current();
		LockState l = state.findLock(lockId, node, false);
		sequenced(l.getOwner(), seqid, Nfs4.OP_LOCKU, () -> {
			state.findLock(lockId, node, true);
			isWrite(locktype);
			state.unlock(l, offset, StateManager.end(offset, length));
			writeStateid(l.stateId());
			return null;
		});
	}

	private void releaseLockOwner() throws Nfs4Exception, IOException {
		long clientid = in.readLong();
		byte[] owner = in.readOpaque(Nfs4.NFS4_OPAQUE_LIMIT);
		state.releaseLockOwner(state.renew(clientid), owner);
	}

	private void close() throws Nfs4Exception, IOException {
		int seqid = in.readInt();
		StateId id = stateid();
		Node node = current();
		OpenState s = openOf(id, node);
		OpenOwner owner = s.getOwner();
		sequenced(owner, seqid, Nfs4.OP_CLOSE, () -> {
			state.find(id, node, true);
			if( !owner.isConfirmed() ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_BAD_STATEID, "open not confirmed");
			}
			state.close(s);
			if( !state.hasOpens(node) ) {
				files.release(node);
			}
			writeStateid(s.stateId());
			return null;
		});
	}
}
