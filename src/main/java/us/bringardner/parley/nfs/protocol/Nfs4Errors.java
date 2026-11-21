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
package us.bringardner.parley.nfs.protocol;

/**
 * What an NFS4ERR_ status means, for people: the server answers a number, and "NFS4 status 2" says nothing to someone
 * who clicked a file that another client had just removed.
 */
public final class Nfs4Errors {

	private Nfs4Errors() {
	}

	/** @return the status in words ("no such file or directory"), or "unknown NFS status 12345" */
	public static String text(int status) {
		switch (status) {
		case Nfs4.NFS4_OK: return "no error";
		case Nfs4.NFS4ERR_PERM: return "operation not permitted";
		case Nfs4.NFS4ERR_NOENT: return "no such file or directory";
		case Nfs4.NFS4ERR_IO: return "the server had an input/output error";
		case Nfs4.NFS4ERR_NXIO: return "no such device or address on the server";
		case Nfs4.NFS4ERR_ACCESS: return "permission denied";
		case Nfs4.NFS4ERR_EXIST: return "already exists";
		case Nfs4.NFS4ERR_XDEV: return "can't move across file systems on the server";
		case Nfs4.NFS4ERR_NOTDIR: return "not a directory";
		case Nfs4.NFS4ERR_ISDIR: return "is a directory";
		case Nfs4.NFS4ERR_INVAL: return "invalid argument";
		case Nfs4.NFS4ERR_FBIG: return "file too large";
		case Nfs4.NFS4ERR_NOSPC: return "no space left on the server";
		case Nfs4.NFS4ERR_ROFS: return "the server's file system is read-only";
		case Nfs4.NFS4ERR_MLINK: return "too many links";
		case Nfs4.NFS4ERR_NAMETOOLONG: return "name too long";
		case Nfs4.NFS4ERR_NOTEMPTY: return "directory not empty";
		case Nfs4.NFS4ERR_DQUOT: return "quota exceeded";
		case Nfs4.NFS4ERR_STALE: return "the file or folder no longer exists on the server (stale file handle)";
		case Nfs4.NFS4ERR_BADHANDLE: return "the server doesn't accept that file handle";
		case Nfs4.NFS4ERR_BAD_COOKIE: return "the directory listing is out of date; list it again";
		case Nfs4.NFS4ERR_NOTSUPP: return "the server doesn't support that operation";
		case Nfs4.NFS4ERR_TOOSMALL: return "the server's reply buffer was too small";
		case Nfs4.NFS4ERR_SERVERFAULT: return "the server had an internal error";
		case Nfs4.NFS4ERR_BADTYPE: return "the server doesn't support that type of file";
		case Nfs4.NFS4ERR_DELAY: return "the server is busy; try again";
		case Nfs4.NFS4ERR_SAME: return "the attribute is unchanged";
		case Nfs4.NFS4ERR_DENIED: return "locked by another user or program";
		case Nfs4.NFS4ERR_EXPIRED: return "the client's lease expired on the server";
		case Nfs4.NFS4ERR_LOCKED: return "the file is locked by another user or program";
		case Nfs4.NFS4ERR_GRACE: return "the server is starting up (grace period); try again soon";
		case Nfs4.NFS4ERR_FHEXPIRED: return "the file handle expired";
		case Nfs4.NFS4ERR_SHARE_DENIED: return "the file is open by another user in a conflicting mode";
		case Nfs4.NFS4ERR_WRONGSEC: return "the server requires a different login method (security flavor)";
		case Nfs4.NFS4ERR_CLID_INUSE: return "this client name is already in use on the server";
		case Nfs4.NFS4ERR_RESOURCE: return "the server is out of resources";
		case Nfs4.NFS4ERR_MOVED: return "the file system has moved to another server";
		case Nfs4.NFS4ERR_NOFILEHANDLE: return "no current file handle";
		case Nfs4.NFS4ERR_MINOR_VERS_MISMATCH: return "the server doesn't support this NFSv4 minor version";
		case Nfs4.NFS4ERR_STALE_CLIENTID: return "the server doesn't know this client (it may have restarted)";
		case Nfs4.NFS4ERR_STALE_STATEID: return "the server doesn't know this open or lock (it may have restarted)";
		case Nfs4.NFS4ERR_OLD_STATEID: return "an old open or lock state id was used";
		case Nfs4.NFS4ERR_BAD_STATEID: return "the server doesn't recognize the open or lock state";
		case Nfs4.NFS4ERR_BAD_SEQID: return "the open or lock sequence number was wrong";
		case Nfs4.NFS4ERR_NOT_SAME: return "the attributes differ";
		case Nfs4.NFS4ERR_LOCK_RANGE: return "that lock range isn't supported";
		case Nfs4.NFS4ERR_SYMLINK: return "a symbolic link is in the way";
		case Nfs4.NFS4ERR_RESTOREFH: return "no saved file handle";
		case Nfs4.NFS4ERR_LEASE_MOVED: return "the lease moved to another server";
		case Nfs4.NFS4ERR_ATTRNOTSUPP: return "the server doesn't support that attribute";
		case Nfs4.NFS4ERR_NO_GRACE: return "the server's grace period is over: the open or lock can't be reclaimed";
		case Nfs4.NFS4ERR_RECLAIM_BAD: return "the reclaim doesn't match what the server had";
		case Nfs4.NFS4ERR_RECLAIM_CONFLICT: return "the reclaim conflicts with another client's";
		case Nfs4.NFS4ERR_BADXDR: return "the server couldn't decode the request";
		case Nfs4.NFS4ERR_LOCKS_HELD: return "locks are still held on the file";
		case Nfs4.NFS4ERR_OPENMODE: return "the file isn't open in a mode that allows that";
		case Nfs4.NFS4ERR_BADOWNER: return "the server doesn't know that owner or group";
		case Nfs4.NFS4ERR_BADCHAR: return "the name has a character the server doesn't accept";
		case Nfs4.NFS4ERR_BADNAME: return "the server doesn't accept that name";
		case Nfs4.NFS4ERR_BAD_RANGE: return "that byte range isn't valid";
		case Nfs4.NFS4ERR_LOCK_NOTSUPP: return "the server doesn't support that kind of lock";
		case Nfs4.NFS4ERR_OP_ILLEGAL: return "the server doesn't know that operation";
		case Nfs4.NFS4ERR_DEADLOCK: return "the lock would cause a deadlock";
		case Nfs4.NFS4ERR_FILE_OPEN: return "the file is open";
		case Nfs4.NFS4ERR_ADMIN_REVOKED: return "the server's administrator revoked the open or lock";
		case Nfs4.NFS4ERR_CB_PATH_DOWN: return "the server can't reach the client's callback address";
		case Nfs4.NFS4ERR_DELEG_REVOKED: return "the server revoked the delegation";
		case Nfs4.NFS4ERR_BADSESSION: return "the server doesn't know this session";
		case Nfs4.NFS4ERR_BADSLOT: return "the session has no such slot";
		case Nfs4.NFS4ERR_COMPLETE_ALREADY: return "the reclaim was already declared complete";
		case Nfs4.NFS4ERR_CONN_NOT_BOUND_TO_SESSION: return "this connection isn't bound to the session";
		case Nfs4.NFS4ERR_BACK_CHAN_BUSY: return "the session already has a callback channel";
		case Nfs4.NFS4ERR_SEQ_MISORDERED: return "the request's sequence number is out of order";
		case Nfs4.NFS4ERR_SEQUENCE_POS: return "SEQUENCE must be the first operation of a compound";
		case Nfs4.NFS4ERR_REQ_TOO_BIG: return "the request is bigger than the session allows";
		case Nfs4.NFS4ERR_REP_TOO_BIG: return "the reply would be bigger than the session allows";
		case Nfs4.NFS4ERR_REP_TOO_BIG_TO_CACHE: return "the reply is too big to keep for a retry";
		case Nfs4.NFS4ERR_RETRY_UNCACHED_REP: return "a retry asked for a reply the server didn't keep";
		case Nfs4.NFS4ERR_UNSAFE_COMPOUND: return "that combination of operations isn't safe to retry";
		case Nfs4.NFS4ERR_TOO_MANY_OPS: return "the compound has more operations than the session allows";
		case Nfs4.NFS4ERR_OP_NOT_IN_SESSION: return "that operation isn't allowed inside a session";
		case Nfs4.NFS4ERR_HASH_ALG_UNSUPP: return "the server doesn't support that hash algorithm";
		case Nfs4.NFS4ERR_CLIENTID_BUSY: return "the client id still has sessions or state";
		case Nfs4.NFS4ERR_SEQ_FALSE_RETRY: return "a request reused a sequence number with different content";
		case Nfs4.NFS4ERR_BAD_HIGH_SLOT: return "the highest slot in the request is out of range";
		case Nfs4.NFS4ERR_DEADSESSION: return "the session has been destroyed";
		case Nfs4.NFS4ERR_ENCR_ALG_UNSUPP: return "the server doesn't support that encryption algorithm";
		case Nfs4.NFS4ERR_NOT_ONLY_OP: return "that operation must be the only one in its compound";
		case Nfs4.NFS4ERR_WRONG_CRED: return "a different credential was used than the one that made the client";
		case Nfs4.NFS4ERR_WRONG_TYPE: return "the file isn't the right type for that operation";
		case Nfs4.NFS4ERR_PARTNER_NOTSUPP: return "the other server doesn't support that copy";
		case Nfs4.NFS4ERR_PARTNER_NO_AUTH: return "the other server refused this server's access";
		case Nfs4.NFS4ERR_UNION_NOTSUPP: return "the server doesn't support that kind of data in a union";
		case Nfs4.NFS4ERR_OFFLOAD_DENIED: return "the server won't do the copy in the background";
		case Nfs4.NFS4ERR_WRONG_LFS: return "the file handle's file system isn't the one the operation needs";
		case Nfs4.NFS4ERR_BADLABEL: return "the security label is not valid";
		case Nfs4.NFS4ERR_OFFLOAD_NO_REQS: return "the copy can't be done as the request asks";
		default: return "unknown NFS status "+status;
		}
	}

	/** @return the status's name ("NFS4ERR_NOENT"), or its number when it is not one this client knows */
	public static String name(int status) {
		switch (status) {
		case Nfs4.NFS4_OK: return "NFS4_OK";
		case Nfs4.NFS4ERR_PERM: return "NFS4ERR_PERM";
		case Nfs4.NFS4ERR_NOENT: return "NFS4ERR_NOENT";
		case Nfs4.NFS4ERR_IO: return "NFS4ERR_IO";
		case Nfs4.NFS4ERR_NXIO: return "NFS4ERR_NXIO";
		case Nfs4.NFS4ERR_ACCESS: return "NFS4ERR_ACCESS";
		case Nfs4.NFS4ERR_EXIST: return "NFS4ERR_EXIST";
		case Nfs4.NFS4ERR_XDEV: return "NFS4ERR_XDEV";
		case Nfs4.NFS4ERR_NOTDIR: return "NFS4ERR_NOTDIR";
		case Nfs4.NFS4ERR_ISDIR: return "NFS4ERR_ISDIR";
		case Nfs4.NFS4ERR_INVAL: return "NFS4ERR_INVAL";
		case Nfs4.NFS4ERR_FBIG: return "NFS4ERR_FBIG";
		case Nfs4.NFS4ERR_NOSPC: return "NFS4ERR_NOSPC";
		case Nfs4.NFS4ERR_ROFS: return "NFS4ERR_ROFS";
		case Nfs4.NFS4ERR_MLINK: return "NFS4ERR_MLINK";
		case Nfs4.NFS4ERR_NAMETOOLONG: return "NFS4ERR_NAMETOOLONG";
		case Nfs4.NFS4ERR_NOTEMPTY: return "NFS4ERR_NOTEMPTY";
		case Nfs4.NFS4ERR_DQUOT: return "NFS4ERR_DQUOT";
		case Nfs4.NFS4ERR_STALE: return "NFS4ERR_STALE";
		case Nfs4.NFS4ERR_BADHANDLE: return "NFS4ERR_BADHANDLE";
		case Nfs4.NFS4ERR_BAD_COOKIE: return "NFS4ERR_BAD_COOKIE";
		case Nfs4.NFS4ERR_NOTSUPP: return "NFS4ERR_NOTSUPP";
		case Nfs4.NFS4ERR_TOOSMALL: return "NFS4ERR_TOOSMALL";
		case Nfs4.NFS4ERR_SERVERFAULT: return "NFS4ERR_SERVERFAULT";
		case Nfs4.NFS4ERR_BADTYPE: return "NFS4ERR_BADTYPE";
		case Nfs4.NFS4ERR_DELAY: return "NFS4ERR_DELAY";
		case Nfs4.NFS4ERR_SAME: return "NFS4ERR_SAME";
		case Nfs4.NFS4ERR_DENIED: return "NFS4ERR_DENIED";
		case Nfs4.NFS4ERR_EXPIRED: return "NFS4ERR_EXPIRED";
		case Nfs4.NFS4ERR_LOCKED: return "NFS4ERR_LOCKED";
		case Nfs4.NFS4ERR_GRACE: return "NFS4ERR_GRACE";
		case Nfs4.NFS4ERR_FHEXPIRED: return "NFS4ERR_FHEXPIRED";
		case Nfs4.NFS4ERR_SHARE_DENIED: return "NFS4ERR_SHARE_DENIED";
		case Nfs4.NFS4ERR_WRONGSEC: return "NFS4ERR_WRONGSEC";
		case Nfs4.NFS4ERR_CLID_INUSE: return "NFS4ERR_CLID_INUSE";
		case Nfs4.NFS4ERR_RESOURCE: return "NFS4ERR_RESOURCE";
		case Nfs4.NFS4ERR_MOVED: return "NFS4ERR_MOVED";
		case Nfs4.NFS4ERR_NOFILEHANDLE: return "NFS4ERR_NOFILEHANDLE";
		case Nfs4.NFS4ERR_MINOR_VERS_MISMATCH: return "NFS4ERR_MINOR_VERS_MISMATCH";
		case Nfs4.NFS4ERR_STALE_CLIENTID: return "NFS4ERR_STALE_CLIENTID";
		case Nfs4.NFS4ERR_STALE_STATEID: return "NFS4ERR_STALE_STATEID";
		case Nfs4.NFS4ERR_OLD_STATEID: return "NFS4ERR_OLD_STATEID";
		case Nfs4.NFS4ERR_BAD_STATEID: return "NFS4ERR_BAD_STATEID";
		case Nfs4.NFS4ERR_BAD_SEQID: return "NFS4ERR_BAD_SEQID";
		case Nfs4.NFS4ERR_NOT_SAME: return "NFS4ERR_NOT_SAME";
		case Nfs4.NFS4ERR_LOCK_RANGE: return "NFS4ERR_LOCK_RANGE";
		case Nfs4.NFS4ERR_SYMLINK: return "NFS4ERR_SYMLINK";
		case Nfs4.NFS4ERR_RESTOREFH: return "NFS4ERR_RESTOREFH";
		case Nfs4.NFS4ERR_LEASE_MOVED: return "NFS4ERR_LEASE_MOVED";
		case Nfs4.NFS4ERR_ATTRNOTSUPP: return "NFS4ERR_ATTRNOTSUPP";
		case Nfs4.NFS4ERR_NO_GRACE: return "NFS4ERR_NO_GRACE";
		case Nfs4.NFS4ERR_RECLAIM_BAD: return "NFS4ERR_RECLAIM_BAD";
		case Nfs4.NFS4ERR_RECLAIM_CONFLICT: return "NFS4ERR_RECLAIM_CONFLICT";
		case Nfs4.NFS4ERR_BADXDR: return "NFS4ERR_BADXDR";
		case Nfs4.NFS4ERR_LOCKS_HELD: return "NFS4ERR_LOCKS_HELD";
		case Nfs4.NFS4ERR_OPENMODE: return "NFS4ERR_OPENMODE";
		case Nfs4.NFS4ERR_BADOWNER: return "NFS4ERR_BADOWNER";
		case Nfs4.NFS4ERR_BADCHAR: return "NFS4ERR_BADCHAR";
		case Nfs4.NFS4ERR_BADNAME: return "NFS4ERR_BADNAME";
		case Nfs4.NFS4ERR_BAD_RANGE: return "NFS4ERR_BAD_RANGE";
		case Nfs4.NFS4ERR_LOCK_NOTSUPP: return "NFS4ERR_LOCK_NOTSUPP";
		case Nfs4.NFS4ERR_OP_ILLEGAL: return "NFS4ERR_OP_ILLEGAL";
		case Nfs4.NFS4ERR_DEADLOCK: return "NFS4ERR_DEADLOCK";
		case Nfs4.NFS4ERR_FILE_OPEN: return "NFS4ERR_FILE_OPEN";
		case Nfs4.NFS4ERR_ADMIN_REVOKED: return "NFS4ERR_ADMIN_REVOKED";
		case Nfs4.NFS4ERR_CB_PATH_DOWN: return "NFS4ERR_CB_PATH_DOWN";
		case Nfs4.NFS4ERR_DELEG_REVOKED: return "NFS4ERR_DELEG_REVOKED";
		case Nfs4.NFS4ERR_BADSESSION: return "NFS4ERR_BADSESSION";
		case Nfs4.NFS4ERR_BADSLOT: return "NFS4ERR_BADSLOT";
		case Nfs4.NFS4ERR_COMPLETE_ALREADY: return "NFS4ERR_COMPLETE_ALREADY";
		case Nfs4.NFS4ERR_CONN_NOT_BOUND_TO_SESSION: return "NFS4ERR_CONN_NOT_BOUND_TO_SESSION";
		case Nfs4.NFS4ERR_BACK_CHAN_BUSY: return "NFS4ERR_BACK_CHAN_BUSY";
		case Nfs4.NFS4ERR_SEQ_MISORDERED: return "NFS4ERR_SEQ_MISORDERED";
		case Nfs4.NFS4ERR_SEQUENCE_POS: return "NFS4ERR_SEQUENCE_POS";
		case Nfs4.NFS4ERR_REQ_TOO_BIG: return "NFS4ERR_REQ_TOO_BIG";
		case Nfs4.NFS4ERR_REP_TOO_BIG: return "NFS4ERR_REP_TOO_BIG";
		case Nfs4.NFS4ERR_REP_TOO_BIG_TO_CACHE: return "NFS4ERR_REP_TOO_BIG_TO_CACHE";
		case Nfs4.NFS4ERR_RETRY_UNCACHED_REP: return "NFS4ERR_RETRY_UNCACHED_REP";
		case Nfs4.NFS4ERR_UNSAFE_COMPOUND: return "NFS4ERR_UNSAFE_COMPOUND";
		case Nfs4.NFS4ERR_TOO_MANY_OPS: return "NFS4ERR_TOO_MANY_OPS";
		case Nfs4.NFS4ERR_OP_NOT_IN_SESSION: return "NFS4ERR_OP_NOT_IN_SESSION";
		case Nfs4.NFS4ERR_HASH_ALG_UNSUPP: return "NFS4ERR_HASH_ALG_UNSUPP";
		case Nfs4.NFS4ERR_CLIENTID_BUSY: return "NFS4ERR_CLIENTID_BUSY";
		case Nfs4.NFS4ERR_SEQ_FALSE_RETRY: return "NFS4ERR_SEQ_FALSE_RETRY";
		case Nfs4.NFS4ERR_BAD_HIGH_SLOT: return "NFS4ERR_BAD_HIGH_SLOT";
		case Nfs4.NFS4ERR_DEADSESSION: return "NFS4ERR_DEADSESSION";
		case Nfs4.NFS4ERR_ENCR_ALG_UNSUPP: return "NFS4ERR_ENCR_ALG_UNSUPP";
		case Nfs4.NFS4ERR_NOT_ONLY_OP: return "NFS4ERR_NOT_ONLY_OP";
		case Nfs4.NFS4ERR_WRONG_CRED: return "NFS4ERR_WRONG_CRED";
		case Nfs4.NFS4ERR_WRONG_TYPE: return "NFS4ERR_WRONG_TYPE";
		case Nfs4.NFS4ERR_PARTNER_NOTSUPP: return "NFS4ERR_PARTNER_NOTSUPP";
		case Nfs4.NFS4ERR_PARTNER_NO_AUTH: return "NFS4ERR_PARTNER_NO_AUTH";
		case Nfs4.NFS4ERR_UNION_NOTSUPP: return "NFS4ERR_UNION_NOTSUPP";
		case Nfs4.NFS4ERR_OFFLOAD_DENIED: return "NFS4ERR_OFFLOAD_DENIED";
		case Nfs4.NFS4ERR_WRONG_LFS: return "NFS4ERR_WRONG_LFS";
		case Nfs4.NFS4ERR_BADLABEL: return "NFS4ERR_BADLABEL";
		case Nfs4.NFS4ERR_OFFLOAD_NO_REQS: return "NFS4ERR_OFFLOAD_NO_REQS";
		default: return Integer.toString(status);
		}
	}

	/** @return the status in words with its name: "no such file or directory (NFS4ERR_NOENT)" */
	public static String describe(int status) {
		String text = text(status);
		return text.startsWith("unknown") ? text : text+" ("+name(status)+")";
	}
}
