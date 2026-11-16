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
 * NFS version 4.0 protocol constants (RFC 7531, the XDR description of RFC 7530).
 *
 * @author Tony Bringardner
 */
public final class Nfs4 {

	public static final int PROGRAM = 100003;
	public static final int VERSION = 4;
	public static final int PROC_NULL = 0;
	public static final int PROC_COMPOUND = 1;
	/** IANA: NFS over TCP */
	public static final int DEFAULT_PORT = 2049;

	public static final int NFS4_FHSIZE = 128;
	public static final int NFS4_VERIFIER_SIZE = 8;
	public static final int NFS4_OPAQUE_LIMIT = 1024;
	/** stateid4.other */
	public static final int NFS4_OTHER_SIZE = 12;

	// ---------------------------------------------------------------- operations (nfs_opnum4)
	public static final int OP_ACCESS = 3;
	public static final int OP_CLOSE = 4;
	public static final int OP_COMMIT = 5;
	public static final int OP_CREATE = 6;
	public static final int OP_DELEGPURGE = 7;
	public static final int OP_DELEGRETURN = 8;
	public static final int OP_GETATTR = 9;
	public static final int OP_GETFH = 10;
	public static final int OP_LINK = 11;
	public static final int OP_LOCK = 12;
	public static final int OP_LOCKT = 13;
	public static final int OP_LOCKU = 14;
	public static final int OP_LOOKUP = 15;
	public static final int OP_LOOKUPP = 16;
	public static final int OP_NVERIFY = 17;
	public static final int OP_OPEN = 18;
	public static final int OP_OPENATTR = 19;
	public static final int OP_OPEN_CONFIRM = 20;
	public static final int OP_OPEN_DOWNGRADE = 21;
	public static final int OP_PUTFH = 22;
	public static final int OP_PUTPUBFH = 23;
	public static final int OP_PUTROOTFH = 24;
	public static final int OP_READ = 25;
	public static final int OP_READDIR = 26;
	public static final int OP_READLINK = 27;
	public static final int OP_REMOVE = 28;
	public static final int OP_RENAME = 29;
	public static final int OP_RENEW = 30;
	public static final int OP_RESTOREFH = 31;
	public static final int OP_SAVEFH = 32;
	public static final int OP_SECINFO = 33;
	public static final int OP_SETATTR = 34;
	public static final int OP_SETCLIENTID = 35;
	public static final int OP_SETCLIENTID_CONFIRM = 36;
	public static final int OP_VERIFY = 37;
	public static final int OP_WRITE = 38;
	public static final int OP_RELEASE_LOCKOWNER = 39;
	public static final int OP_ILLEGAL = 10044;

	// ---------------------------------------------------------------- status (nfsstat4)
	public static final int NFS4_OK = 0;
	public static final int NFS4ERR_PERM = 1;
	public static final int NFS4ERR_NOENT = 2;
	public static final int NFS4ERR_IO = 5;
	public static final int NFS4ERR_NXIO = 6;
	public static final int NFS4ERR_ACCESS = 13;
	public static final int NFS4ERR_EXIST = 17;
	public static final int NFS4ERR_XDEV = 18;
	public static final int NFS4ERR_NOTDIR = 20;
	public static final int NFS4ERR_ISDIR = 21;
	public static final int NFS4ERR_INVAL = 22;
	public static final int NFS4ERR_FBIG = 27;
	public static final int NFS4ERR_NOSPC = 28;
	public static final int NFS4ERR_ROFS = 30;
	public static final int NFS4ERR_MLINK = 31;
	public static final int NFS4ERR_NAMETOOLONG = 63;
	public static final int NFS4ERR_NOTEMPTY = 66;
	public static final int NFS4ERR_DQUOT = 69;
	public static final int NFS4ERR_STALE = 70;
	public static final int NFS4ERR_BADHANDLE = 10001;
	public static final int NFS4ERR_BAD_COOKIE = 10003;
	public static final int NFS4ERR_NOTSUPP = 10004;
	public static final int NFS4ERR_TOOSMALL = 10005;
	public static final int NFS4ERR_SERVERFAULT = 10006;
	public static final int NFS4ERR_BADTYPE = 10007;
	public static final int NFS4ERR_DELAY = 10008;
	public static final int NFS4ERR_SAME = 10009;
	public static final int NFS4ERR_DENIED = 10010;
	public static final int NFS4ERR_EXPIRED = 10011;
	public static final int NFS4ERR_LOCKED = 10012;
	public static final int NFS4ERR_GRACE = 10013;
	public static final int NFS4ERR_FHEXPIRED = 10014;
	public static final int NFS4ERR_SHARE_DENIED = 10015;
	public static final int NFS4ERR_WRONGSEC = 10016;
	public static final int NFS4ERR_CLID_INUSE = 10017;
	public static final int NFS4ERR_RESOURCE = 10018;
	public static final int NFS4ERR_MOVED = 10019;
	public static final int NFS4ERR_NOFILEHANDLE = 10020;
	public static final int NFS4ERR_MINOR_VERS_MISMATCH = 10021;
	public static final int NFS4ERR_STALE_CLIENTID = 10022;
	public static final int NFS4ERR_STALE_STATEID = 10023;
	public static final int NFS4ERR_OLD_STATEID = 10024;
	public static final int NFS4ERR_BAD_STATEID = 10025;
	public static final int NFS4ERR_BAD_SEQID = 10026;
	public static final int NFS4ERR_NOT_SAME = 10027;
	public static final int NFS4ERR_LOCK_RANGE = 10028;
	public static final int NFS4ERR_SYMLINK = 10029;
	public static final int NFS4ERR_RESTOREFH = 10030;
	public static final int NFS4ERR_LEASE_MOVED = 10031;
	public static final int NFS4ERR_ATTRNOTSUPP = 10032;
	public static final int NFS4ERR_NO_GRACE = 10033;
	public static final int NFS4ERR_RECLAIM_BAD = 10034;
	public static final int NFS4ERR_RECLAIM_CONFLICT = 10035;
	public static final int NFS4ERR_BADXDR = 10036;
	public static final int NFS4ERR_LOCKS_HELD = 10037;
	public static final int NFS4ERR_OPENMODE = 10038;
	public static final int NFS4ERR_BADOWNER = 10039;
	public static final int NFS4ERR_BADCHAR = 10040;
	public static final int NFS4ERR_BADNAME = 10041;
	public static final int NFS4ERR_BAD_RANGE = 10042;
	public static final int NFS4ERR_LOCK_NOTSUPP = 10043;
	public static final int NFS4ERR_OP_ILLEGAL = 10044;
	public static final int NFS4ERR_DEADLOCK = 10045;
	public static final int NFS4ERR_FILE_OPEN = 10046;
	public static final int NFS4ERR_ADMIN_REVOKED = 10047;
	public static final int NFS4ERR_CB_PATH_DOWN = 10048;

	// ---------------------------------------------------------------- nfs_ftype4
	public static final int NF4REG = 1;
	public static final int NF4DIR = 2;
	public static final int NF4BLK = 3;
	public static final int NF4CHR = 4;
	public static final int NF4LNK = 5;
	public static final int NF4SOCK = 6;
	public static final int NF4FIFO = 7;
	public static final int NF4ATTRDIR = 8;
	public static final int NF4NAMEDATTR = 9;

	// ---------------------------------------------------------------- fh_expire_type
	public static final int FH4_PERSISTENT = 0x00;
	public static final int FH4_NOEXPIRE_WITH_OPEN = 0x01;
	public static final int FH4_VOLATILE_ANY = 0x02;
	public static final int FH4_VOL_MIGRATION = 0x04;
	public static final int FH4_VOL_RENAME = 0x08;

	// ---------------------------------------------------------------- attributes
	public static final int FATTR4_SUPPORTED_ATTRS = 0;
	public static final int FATTR4_TYPE = 1;
	public static final int FATTR4_FH_EXPIRE_TYPE = 2;
	public static final int FATTR4_CHANGE = 3;
	public static final int FATTR4_SIZE = 4;
	public static final int FATTR4_LINK_SUPPORT = 5;
	public static final int FATTR4_SYMLINK_SUPPORT = 6;
	public static final int FATTR4_NAMED_ATTR = 7;
	public static final int FATTR4_FSID = 8;
	public static final int FATTR4_UNIQUE_HANDLES = 9;
	public static final int FATTR4_LEASE_TIME = 10;
	public static final int FATTR4_RDATTR_ERROR = 11;
	public static final int FATTR4_ACL = 12;
	public static final int FATTR4_ACLSUPPORT = 13;
	public static final int FATTR4_ARCHIVE = 14;
	public static final int FATTR4_CANSETTIME = 15;
	public static final int FATTR4_CASE_INSENSITIVE = 16;
	public static final int FATTR4_CASE_PRESERVING = 17;
	public static final int FATTR4_CHOWN_RESTRICTED = 18;
	public static final int FATTR4_FILEHANDLE = 19;
	public static final int FATTR4_FILEID = 20;
	public static final int FATTR4_FILES_AVAIL = 21;
	public static final int FATTR4_FILES_FREE = 22;
	public static final int FATTR4_FILES_TOTAL = 23;
	public static final int FATTR4_FS_LOCATIONS = 24;
	public static final int FATTR4_HIDDEN = 25;
	public static final int FATTR4_HOMOGENEOUS = 26;
	public static final int FATTR4_MAXFILESIZE = 27;
	public static final int FATTR4_MAXLINK = 28;
	public static final int FATTR4_MAXNAME = 29;
	public static final int FATTR4_MAXREAD = 30;
	public static final int FATTR4_MAXWRITE = 31;
	public static final int FATTR4_MIMETYPE = 32;
	public static final int FATTR4_MODE = 33;
	public static final int FATTR4_NO_TRUNC = 34;
	public static final int FATTR4_NUMLINKS = 35;
	public static final int FATTR4_OWNER = 36;
	public static final int FATTR4_OWNER_GROUP = 37;
	public static final int FATTR4_QUOTA_AVAIL_HARD = 38;
	public static final int FATTR4_QUOTA_AVAIL_SOFT = 39;
	public static final int FATTR4_QUOTA_USED = 40;
	public static final int FATTR4_RAWDEV = 41;
	public static final int FATTR4_SPACE_AVAIL = 42;
	public static final int FATTR4_SPACE_FREE = 43;
	public static final int FATTR4_SPACE_TOTAL = 44;
	public static final int FATTR4_SPACE_USED = 45;
	public static final int FATTR4_SYSTEM = 46;
	public static final int FATTR4_TIME_ACCESS = 47;
	public static final int FATTR4_TIME_ACCESS_SET = 48;
	public static final int FATTR4_TIME_BACKUP = 49;
	public static final int FATTR4_TIME_CREATE = 50;
	public static final int FATTR4_TIME_DELTA = 51;
	public static final int FATTR4_TIME_METADATA = 52;
	public static final int FATTR4_TIME_MODIFY = 53;
	public static final int FATTR4_TIME_MODIFY_SET = 54;
	public static final int FATTR4_MOUNTED_ON_FILEID = 55;

	// time_how4
	public static final int SET_TO_SERVER_TIME4 = 0;
	public static final int SET_TO_CLIENT_TIME4 = 1;

	// ---------------------------------------------------------------- ACCESS
	public static final int ACCESS4_READ = 0x01;
	public static final int ACCESS4_LOOKUP = 0x02;
	public static final int ACCESS4_MODIFY = 0x04;
	public static final int ACCESS4_EXTEND = 0x08;
	public static final int ACCESS4_DELETE = 0x10;
	public static final int ACCESS4_EXECUTE = 0x20;

	// ---------------------------------------------------------------- OPEN
	public static final int OPEN4_SHARE_ACCESS_READ = 0x01;
	public static final int OPEN4_SHARE_ACCESS_WRITE = 0x02;
	public static final int OPEN4_SHARE_ACCESS_BOTH = 0x03;
	public static final int OPEN4_SHARE_DENY_NONE = 0x00;
	public static final int OPEN4_SHARE_DENY_READ = 0x01;
	public static final int OPEN4_SHARE_DENY_WRITE = 0x02;
	public static final int OPEN4_SHARE_DENY_BOTH = 0x03;

	// opentype4
	public static final int OPEN4_NOCREATE = 0;
	public static final int OPEN4_CREATE = 1;
	// createmode4
	public static final int UNCHECKED4 = 0;
	public static final int GUARDED4 = 1;
	public static final int EXCLUSIVE4 = 2;
	// open_claim_type4
	public static final int CLAIM_NULL = 0;
	public static final int CLAIM_PREVIOUS = 1;
	public static final int CLAIM_DELEGATE_CUR = 2;
	public static final int CLAIM_DELEGATE_PREV = 3;
	// open_delegation_type4
	public static final int OPEN_DELEGATE_NONE = 0;
	public static final int OPEN_DELEGATE_READ = 1;
	public static final int OPEN_DELEGATE_WRITE = 2;
	// OPEN rflags
	public static final int OPEN4_RESULT_CONFIRM = 0x02;
	public static final int OPEN4_RESULT_LOCKTYPE_POSIX = 0x04;

	// ---------------------------------------------------------------- WRITE stable_how4
	public static final int UNSTABLE4 = 0;
	public static final int DATA_SYNC4 = 1;
	public static final int FILE_SYNC4 = 2;

	// ---------------------------------------------------------------- mode4 bits
	public static final int MODE4_RUSR = 0x100;
	public static final int MODE4_WUSR = 0x080;
	public static final int MODE4_XUSR = 0x040;
	public static final int MODE4_RGRP = 0x020;
	public static final int MODE4_WGRP = 0x010;
	public static final int MODE4_XGRP = 0x008;
	public static final int MODE4_ROTH = 0x004;
	public static final int MODE4_WOTH = 0x002;
	public static final int MODE4_XOTH = 0x001;

	private Nfs4() {
	}

	/** @return the operation's name, for logging */
	public static String opName(int op) {
		switch (op) {
		case OP_ACCESS: return "ACCESS";
		case OP_CLOSE: return "CLOSE";
		case OP_COMMIT: return "COMMIT";
		case OP_CREATE: return "CREATE";
		case OP_DELEGPURGE: return "DELEGPURGE";
		case OP_DELEGRETURN: return "DELEGRETURN";
		case OP_GETATTR: return "GETATTR";
		case OP_GETFH: return "GETFH";
		case OP_LINK: return "LINK";
		case OP_LOCK: return "LOCK";
		case OP_LOCKT: return "LOCKT";
		case OP_LOCKU: return "LOCKU";
		case OP_LOOKUP: return "LOOKUP";
		case OP_LOOKUPP: return "LOOKUPP";
		case OP_NVERIFY: return "NVERIFY";
		case OP_OPEN: return "OPEN";
		case OP_OPENATTR: return "OPENATTR";
		case OP_OPEN_CONFIRM: return "OPEN_CONFIRM";
		case OP_OPEN_DOWNGRADE: return "OPEN_DOWNGRADE";
		case OP_PUTFH: return "PUTFH";
		case OP_PUTPUBFH: return "PUTPUBFH";
		case OP_PUTROOTFH: return "PUTROOTFH";
		case OP_READ: return "READ";
		case OP_READDIR: return "READDIR";
		case OP_READLINK: return "READLINK";
		case OP_REMOVE: return "REMOVE";
		case OP_RENAME: return "RENAME";
		case OP_RENEW: return "RENEW";
		case OP_RESTOREFH: return "RESTOREFH";
		case OP_SAVEFH: return "SAVEFH";
		case OP_SECINFO: return "SECINFO";
		case OP_SETATTR: return "SETATTR";
		case OP_SETCLIENTID: return "SETCLIENTID";
		case OP_SETCLIENTID_CONFIRM: return "SETCLIENTID_CONFIRM";
		case OP_VERIFY: return "VERIFY";
		case OP_WRITE: return "WRITE";
		case OP_RELEASE_LOCKOWNER: return "RELEASE_LOCKOWNER";
		default: return "ILLEGAL("+op+")";
		}
	}

	// nfs_lock_type4
	public static final int READ_LT = 1;
	public static final int WRITE_LT = 2;
	/** blocking read lock: answered at once like READ_LT (decision D19) */
	public static final int READW_LT = 3;
	/** blocking write lock: answered at once like WRITE_LT (decision D19) */
	public static final int WRITEW_LT = 4;
}
