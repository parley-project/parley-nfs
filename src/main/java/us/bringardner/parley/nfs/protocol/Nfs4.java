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
	public static final int OP_BACKCHANNEL_CTL = 40;
	public static final int OP_BIND_CONN_TO_SESSION = 41;
	public static final int OP_EXCHANGE_ID = 42;
	public static final int OP_CREATE_SESSION = 43;
	public static final int OP_DESTROY_SESSION = 44;
	public static final int OP_FREE_STATEID = 45;
	public static final int OP_GET_DIR_DELEGATION = 46;
	public static final int OP_SECINFO_NO_NAME = 52;
	public static final int OP_SEQUENCE = 53;
	public static final int OP_TEST_STATEID = 55;
	public static final int OP_WANT_DELEGATION = 56;
	public static final int OP_DESTROY_CLIENTID = 57;
	public static final int OP_RECLAIM_COMPLETE = 58;
	public static final int OP_ALLOCATE = 59;
	public static final int OP_COPY = 60;
	public static final int OP_COPY_NOTIFY = 61;
	public static final int OP_DEALLOCATE = 62;
	public static final int OP_IO_ADVISE = 63;
	public static final int OP_LAYOUTERROR = 64;
	public static final int OP_LAYOUTSTATS = 65;
	public static final int OP_OFFLOAD_CANCEL = 66;
	public static final int OP_OFFLOAD_STATUS = 67;
	public static final int OP_READ_PLUS = 68;
	public static final int OP_SEEK = 69;
	public static final int OP_WRITE_SAME = 70;
	public static final int OP_CLONE = 71;
	// RFC 8276 extended attributes (D101)
	public static final int OP_GETXATTR = 72;
	public static final int OP_SETXATTR = 73;
	public static final int OP_LISTXATTRS = 74;
	public static final int OP_REMOVEXATTR = 75;
	/** setxattr_option4 */
	public static final int SETXATTR4_EITHER = 0;
	public static final int SETXATTR4_CREATE = 1;
	public static final int SETXATTR4_REPLACE = 2;
	/** SEEK: what to look for (RFC 7862 section 15.11) */
	public static final int NFS4_CONTENT_DATA = 0;
	public static final int NFS4_CONTENT_HOLE = 1;
	public static final int OP_ILLEGAL = 10044;
	/** the size of a session id (RFC 8881 section 3.3.12) */
	public static final int NFS4_SESSIONID_SIZE = 16;

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
	public static final int NFS4ERR_DELEG_REVOKED = 10087;
	public static final int NFS4ERR_BADSESSION = 10052;
	public static final int NFS4ERR_BADSLOT = 10053;
	public static final int NFS4ERR_COMPLETE_ALREADY = 10054;
	public static final int NFS4ERR_CONN_NOT_BOUND_TO_SESSION = 10055;
	public static final int NFS4ERR_BACK_CHAN_BUSY = 10057;
	public static final int NFS4ERR_SEQ_MISORDERED = 10063;
	public static final int NFS4ERR_SEQUENCE_POS = 10064;
	public static final int NFS4ERR_REQ_TOO_BIG = 10065;
	public static final int NFS4ERR_REP_TOO_BIG = 10066;
	public static final int NFS4ERR_REP_TOO_BIG_TO_CACHE = 10067;
	public static final int NFS4ERR_RETRY_UNCACHED_REP = 10068;
	public static final int NFS4ERR_UNSAFE_COMPOUND = 10069;
	public static final int NFS4ERR_TOO_MANY_OPS = 10070;
	public static final int NFS4ERR_OP_NOT_IN_SESSION = 10071;
	public static final int NFS4ERR_HASH_ALG_UNSUPP = 10072;
	public static final int NFS4ERR_CLIENTID_BUSY = 10074;
	public static final int NFS4ERR_SEQ_FALSE_RETRY = 10076;
	public static final int NFS4ERR_BAD_HIGH_SLOT = 10077;
	public static final int NFS4ERR_DEADSESSION = 10078;
	public static final int NFS4ERR_ENCR_ALG_UNSUPP = 10079;
	public static final int NFS4ERR_NOT_ONLY_OP = 10081;
	public static final int NFS4ERR_WRONG_CRED = 10082;
	public static final int NFS4ERR_WRONG_TYPE = 10083;
	public static final int NFS4ERR_PARTNER_NOTSUPP = 10088;
	public static final int NFS4ERR_PARTNER_NO_AUTH = 10089;
	public static final int NFS4ERR_UNION_NOTSUPP = 10090;
	public static final int NFS4ERR_OFFLOAD_DENIED = 10091;
	public static final int NFS4ERR_WRONG_LFS = 10092;
	public static final int NFS4ERR_BADLABEL = 10093;
	public static final int NFS4ERR_OFFLOAD_NO_REQS = 10094;
	/** RFC 8276 */
	public static final int NFS4ERR_NOXATTR = 10095;
	public static final int NFS4ERR_XATTR2BIG = 10096;

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
	/** RFC 8276: true when the file system keeps extended attributes */
	// IO_ADVISE hints (RFC 7862 section 15.5.1): the number of a hint is its bit in the hints bitmap
	public static final int IO_ADVISE4_NORMAL = 0;
	public static final int IO_ADVISE4_SEQUENTIAL = 1;
	public static final int IO_ADVISE4_SEQUENTIAL_BACKWARDS = 2;
	public static final int IO_ADVISE4_RANDOM = 3;
	public static final int IO_ADVISE4_WILLNEED = 4;
	public static final int IO_ADVISE4_WILLNEED_OPPORTUNISTIC = 5;
	public static final int IO_ADVISE4_DONTNEED = 6;
	public static final int IO_ADVISE4_NOREUSE = 7;
	public static final int IO_ADVISE4_READ = 8;
	public static final int IO_ADVISE4_WRITE = 9;
	public static final int IO_ADVISE4_INIT_PROXIMITY = 10;
	/** the unit CLONE works in (RFC 7862), a uint32 */
	public static final int FATTR4_CLONE_BLKSIZE = 77;
	public static final int FATTR4_XATTR_SUPPORT = 82;

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
	/** RFC 8276 section 8.5: GETXATTR, SETXATTR and REMOVEXATTR, LISTXATTRS */
	public static final int ACCESS4_XAREAD = 0x40;
	public static final int ACCESS4_XAWRITE = 0x80;
	public static final int ACCESS4_XALIST = 0x100;

	// ---------------------------------------------------------------- ACL (RFC 7530 section 6)
	public static final int ACE4_ACCESS_ALLOWED_ACE_TYPE = 0;
	public static final int ACE4_ACCESS_DENIED_ACE_TYPE = 1;
	public static final int ACE4_SYSTEM_AUDIT_ACE_TYPE = 2;
	public static final int ACE4_SYSTEM_ALARM_ACE_TYPE = 3;

	public static final int ACE4_FILE_INHERIT_ACE = 0x01;
	public static final int ACE4_DIRECTORY_INHERIT_ACE = 0x02;
	public static final int ACE4_NO_PROPAGATE_INHERIT_ACE = 0x04;
	public static final int ACE4_INHERIT_ONLY_ACE = 0x08;
	public static final int ACE4_SUCCESSFUL_ACCESS_ACE_FLAG = 0x10;
	public static final int ACE4_FAILED_ACCESS_ACE_FLAG = 0x20;
	public static final int ACE4_IDENTIFIER_GROUP = 0x40;

	public static final int ACE4_READ_DATA = 0x00000001;
	public static final int ACE4_LIST_DIRECTORY = 0x00000001;
	public static final int ACE4_WRITE_DATA = 0x00000002;
	public static final int ACE4_ADD_FILE = 0x00000002;
	public static final int ACE4_APPEND_DATA = 0x00000004;
	public static final int ACE4_ADD_SUBDIRECTORY = 0x00000004;
	public static final int ACE4_READ_NAMED_ATTRS = 0x00000008;
	public static final int ACE4_WRITE_NAMED_ATTRS = 0x00000010;
	public static final int ACE4_EXECUTE = 0x00000020;
	public static final int ACE4_DELETE_CHILD = 0x00000040;
	public static final int ACE4_READ_ATTRIBUTES = 0x00000080;
	public static final int ACE4_WRITE_ATTRIBUTES = 0x00000100;
	public static final int ACE4_DELETE = 0x00010000;
	public static final int ACE4_READ_ACL = 0x00020000;
	public static final int ACE4_WRITE_ACL = 0x00040000;
	public static final int ACE4_WRITE_OWNER = 0x00080000;
	public static final int ACE4_SYNCHRONIZE = 0x00100000;
	/** every mask bit RFC 7530 defines */
	public static final int ACE4_ALL_MASK = 0x001f01ff;

	public static final int ACL4_SUPPORT_ALLOW_ACL = 0x01;
	public static final int ACL4_SUPPORT_DENY_ACL = 0x02;
	public static final int ACL4_SUPPORT_AUDIT_ACL = 0x04;
	public static final int ACL4_SUPPORT_ALARM_ACL = 0x08;

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
	/** The callback side (RFC 7530 section 20): the client's program is version 1, proc 0 is CB_NULL, 1 is CB_COMPOUND */
	public static final int NFS4_CALLBACK_VERSION = 1;
	public static final int CB_NULL = 0;
	public static final int CB_COMPOUND = 1;
	public static final int OP_CB_RECALL = 4;
	/** CB_NOTIFY (RFC 8881 section 20.4): the changes of a directory a client holds a delegation of */
	public static final int OP_CB_NOTIFY = 6;
	/** the kinds of change CB_NOTIFY tells of, as bit numbers of the notification bitmap (RFC 8881 section 20.4) */
	public static final int NOTIFY4_REMOVE_ENTRY = 2;
	public static final int NOTIFY4_ADD_ENTRY = 3;
	public static final int NOTIFY4_RENAME_ENTRY = 4;
	/** GET_DIR_DELEGATION: the answers (RFC 8881 section 18.39) */
	public static final int GDD4_OK = 0;
	public static final int GDD4_UNAVAIL = 1;
	/** NFSv4.1 callback operations (RFC 8881 section 20) */
	public static final int OP_CB_SEQUENCE = 11;

	public static final int OPEN_DELEGATE_NONE = 0;
	public static final int OPEN_DELEGATE_READ = 1;
	public static final int OPEN_DELEGATE_WRITE = 2;
	/** nfs_space_limit4: limited by a size (RFC 7530 section 16.16) */
	public static final int NFS_LIMIT_SIZE = 1;
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
		case OP_BACKCHANNEL_CTL: return "BACKCHANNEL_CTL";
		case OP_BIND_CONN_TO_SESSION: return "BIND_CONN_TO_SESSION";
		case OP_EXCHANGE_ID: return "EXCHANGE_ID";
		case OP_CREATE_SESSION: return "CREATE_SESSION";
		case OP_DESTROY_SESSION: return "DESTROY_SESSION";
		case OP_FREE_STATEID: return "FREE_STATEID";
		case OP_GET_DIR_DELEGATION: return "GET_DIR_DELEGATION";
		case OP_SECINFO_NO_NAME: return "SECINFO_NO_NAME";
		case OP_SEQUENCE: return "SEQUENCE";
		case OP_TEST_STATEID: return "TEST_STATEID";
		case OP_WANT_DELEGATION: return "WANT_DELEGATION";
		case OP_DESTROY_CLIENTID: return "DESTROY_CLIENTID";
		case OP_RECLAIM_COMPLETE: return "RECLAIM_COMPLETE";
		case OP_ALLOCATE: return "ALLOCATE";
		case OP_COPY: return "COPY";
		case OP_COPY_NOTIFY: return "COPY_NOTIFY";
		case OP_DEALLOCATE: return "DEALLOCATE";
		case OP_IO_ADVISE: return "IO_ADVISE";
		case OP_LAYOUTERROR: return "LAYOUTERROR";
		case OP_LAYOUTSTATS: return "LAYOUTSTATS";
		case OP_OFFLOAD_CANCEL: return "OFFLOAD_CANCEL";
		case OP_OFFLOAD_STATUS: return "OFFLOAD_STATUS";
		case OP_READ_PLUS: return "READ_PLUS";
		case OP_SEEK: return "SEEK";
		case OP_WRITE_SAME: return "WRITE_SAME";
		case OP_CLONE: return "CLONE";
		case OP_GETXATTR: return "GETXATTR";
		case OP_SETXATTR: return "SETXATTR";
		case OP_LISTXATTRS: return "LISTXATTRS";
		case OP_REMOVEXATTR: return "REMOVEXATTR";
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
