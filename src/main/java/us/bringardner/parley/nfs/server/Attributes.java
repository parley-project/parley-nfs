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

import java.util.ArrayList;
import java.util.List;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.XdrDecoder;
import us.bringardner.parley.nfs.rpc.XdrEncoder;
import us.bringardner.parley.nfs.rpc.XdrException;

/**
 * File attributes (RFC 7530 section 5): which are supported, encoding fattr4 for GETATTR and
 * READDIR, and decoding the attributes a client sets.
 * <p>
 * What a FileSource doesn't have is answered the way RFC 7530 allows: no hard links
 * (link_support false, numlinks 1), symbolic links are followed (symlink_support false), no
 * named attributes or ACLs, and the metadata time is the modify time. Space (df) is the
 * export's FileSource's own (0 where it can't tell); file counts aren't supported (decisions D9, D24).
 *
 * @author Tony Bringardner
 */
public class Attributes {

	/** The attributes this server returns. */
	public static final int[] SUPPORTED = bitmap(
			Nfs4.FATTR4_SUPPORTED_ATTRS, Nfs4.FATTR4_TYPE, Nfs4.FATTR4_FH_EXPIRE_TYPE, Nfs4.FATTR4_CHANGE,
			Nfs4.FATTR4_SIZE, Nfs4.FATTR4_LINK_SUPPORT, Nfs4.FATTR4_SYMLINK_SUPPORT, Nfs4.FATTR4_NAMED_ATTR,
			Nfs4.FATTR4_FSID, Nfs4.FATTR4_UNIQUE_HANDLES, Nfs4.FATTR4_LEASE_TIME, Nfs4.FATTR4_RDATTR_ERROR,
			Nfs4.FATTR4_ACL, Nfs4.FATTR4_ACLSUPPORT, Nfs4.FATTR4_CANSETTIME, Nfs4.FATTR4_CASE_INSENSITIVE,
			Nfs4.FATTR4_CASE_PRESERVING, Nfs4.FATTR4_CHOWN_RESTRICTED, Nfs4.FATTR4_FILEHANDLE,
			Nfs4.FATTR4_FILEID, Nfs4.FATTR4_HIDDEN, Nfs4.FATTR4_HOMOGENEOUS, Nfs4.FATTR4_MAXFILESIZE,
			Nfs4.FATTR4_MAXLINK, Nfs4.FATTR4_MAXNAME, Nfs4.FATTR4_MAXREAD, Nfs4.FATTR4_MAXWRITE,
			Nfs4.FATTR4_MODE, Nfs4.FATTR4_NO_TRUNC, Nfs4.FATTR4_NUMLINKS, Nfs4.FATTR4_OWNER,
			Nfs4.FATTR4_OWNER_GROUP, Nfs4.FATTR4_RAWDEV, Nfs4.FATTR4_TIME_ACCESS, Nfs4.FATTR4_TIME_ACCESS_SET,
			Nfs4.FATTR4_TIME_CREATE, Nfs4.FATTR4_TIME_DELTA, Nfs4.FATTR4_TIME_METADATA,
			Nfs4.FATTR4_TIME_MODIFY, Nfs4.FATTR4_TIME_MODIFY_SET, Nfs4.FATTR4_MOUNTED_ON_FILEID,
			Nfs4.FATTR4_SPACE_AVAIL, Nfs4.FATTR4_SPACE_FREE, Nfs4.FATTR4_SPACE_TOTAL);

	/** The attributes a client can set. */
	public static final int[] WRITABLE = bitmap(Nfs4.FATTR4_SIZE, Nfs4.FATTR4_ACL, Nfs4.FATTR4_MODE, Nfs4.FATTR4_OWNER,
			Nfs4.FATTR4_OWNER_GROUP, Nfs4.FATTR4_TIME_ACCESS_SET, Nfs4.FATTR4_TIME_MODIFY_SET);

	/** Attributes that are only set, never returned by GETATTR. */
	private static final int[] WRITE_ONLY = bitmap(Nfs4.FATTR4_TIME_ACCESS_SET, Nfs4.FATTR4_TIME_MODIFY_SET);

	/**
	 * The most words of a bitmap4 a client may send (attributes up to 2047): words beyond the
	 * attributes this server knows are read and ignored, as RFC 7530 section 5.1 asks.
	 */
	public static final int MAX_BITMAP_WORDS = 64;

	public static final String NOBODY = "nobody";

	private final String domain;
	/** owners in it are sent as their uid numbers (decision D15); may be null */
	private final IdNumbers numbers;
	private final long leaseSeconds;
	private final long maxRead;
	private final long maxWrite;

	/**
	 * @param domain the domain of owner names (owner@domain), which clients must agree on
	 * @param leaseSeconds the lease_time attribute
	 * @param maxRead the most a READ returns
	 * @param maxWrite the most a WRITE takes
	 */
	public Attributes(String domain, long leaseSeconds, long maxRead, long maxWrite) {
		this(domain, null, leaseSeconds, maxRead, maxWrite);
	}

	/**
	 * @param domain the domain of owner names (owner@domain), which clients must agree on
	 * @param idMap owners whose names are in it are sent as their uid numbers; null for none
	 * @param leaseSeconds the lease_time attribute
	 * @param maxRead the most a READ returns
	 * @param maxWrite the most a WRITE takes
	 */
	public Attributes(String domain, IdNumbers numbers, long leaseSeconds, long maxRead, long maxWrite) {
		this.domain = domain;
		this.numbers = numbers;
		this.leaseSeconds = leaseSeconds;
		this.maxRead = maxRead;
		this.maxWrite = maxWrite;
	}

	public String getDomain() {
		return domain;
	}

	// ------------------------------------------------------------ bitmaps

	public static int[] bitmap(int... bits) {
		int max = 0;
		for (int b : bits) {
			max = Math.max(max, b);
		}
		int[] ret = new int[max/32+1];
		for (int b : bits) {
			ret[b/32] |= 1 << (b%32);
		}
		return ret;
	}

	/** Turn an attribute's bit off in a bitmap. */
	static void clearBit(int[] bitmap, int bit) {
		if( bit/32 < bitmap.length ) {
			bitmap[bit/32] &= ~(1 << (bit%32));
		}
	}

	public static boolean isSet(int[] bitmap, int bit) {
		int w = bit/32;
		return w < bitmap.length && (bitmap[w] & (1 << (bit%32))) != 0;
	}

	/** @return a and b, without trailing zero words */
	public static int[] and(int[] a, int[] b) {
		int n = Math.min(a.length, b.length);
		while( n > 0 && (a[n-1] & b[n-1]) == 0 ) {
			n--;
		}
		int[] ret = new int[n];
		for (int i = 0; i < n; i++) {
			ret[i] = a[i] & b[i];
		}
		return ret;
	}

	/** @return true if bitmap asks for a write-only attribute (GETATTR and READDIR answer NFS4ERR_INVAL) */
	public static boolean hasWriteOnly(int[] bitmap) {
		for (int i = 0; i < bitmap.length && i < WRITE_ONLY.length; i++) {
			if( (bitmap[i] & WRITE_ONLY[i]) != 0 ) {
				return true;
			}
		}
		return false;
	}

	/** @return true if bitmap has a bit not in allowed */
	public static boolean hasOtherThan(int[] bitmap, int[] allowed) {
		for (int i = 0; i < bitmap.length; i++) {
			int a = i < allowed.length ? allowed[i] : 0;
			if( (bitmap[i] & ~a) != 0 ) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------ GETATTR

	/** @return what a symbolic link says, exactly as stored, or null when the file isn't one (decision D72) */
	static String linkText(FileSource f) {
		try {
			return f.getLinkText();
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** @return true for a symbolic link, one that points at nothing included (which exists() doesn't see) */
	static boolean isLink(FileSource f) {
		return linkText(f) != null;
	}

	/** @return true if the file or a symbolic link of that name is there */
	static boolean present(FileSource f) throws IOException {
		return f.exists() || isLink(f);
	}

	/** What one file's attributes are read from, each read once. */
	public static final class Source {
		final Export export;
		final Node node;
		final FileSource file;
		final Long openLength;
		private Boolean dir;
		private Long modified;
		private String link;
		private boolean linkRead;

		/**
		 * @param openLength the size from an open stream (written data the file hasn't seen yet), or null
		 */
		public Source(Export export, Node node, FileSource file, Long openLength) {
			this.export = export;
			this.node = node;
			this.file = file;
			this.openLength = openLength;
		}

		/** @return what the file says if it is a symbolic link (never followed), else null */
		String linkText() {
			if( !linkRead ) {
				link = Attributes.linkText(file);
				linkRead = true;
			}
			return link;
		}

		boolean isDirectory() throws IOException {
			if( dir == null ) {
				dir = linkText() == null && file.isDirectory();
			}
			return dir;
		}

		long modified() throws IOException {
			if( modified == null ) {
				modified = file.lastModified();
			}
			return modified;
		}

		long length() throws IOException {
			if( linkText() != null ) {
				return linkText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
			}
			if( openLength != null ) {
				return openLength;
			}
			return isDirectory() ? 4096 : file.length();
		}
	}

	/**
	 * Write a fattr4: the bitmap of what is returned, then the values.
	 *
	 * @param requested the attributes asked for; those not supported (and the write-only ones) are left out
	 * @throws IOException if the file can't be read
	 */
	public void encode(Source src, int[] requested, XdrEncoder out) throws IOException {
		int[] bits = and(requested, SUPPORTED);
		if( src.export.getAcls() == null ) {
			// ACLs are off without a state folder (decision D51)
			clearBit(bits, Nfs4.FATTR4_ACL);
		}
		for (int i = 0; i < bits.length && i < WRITE_ONLY.length; i++) {
			bits[i] &= ~WRITE_ONLY[i];
		}
		XdrEncoder values = new XdrEncoder(256);
		for (int w = 0; w < bits.length; w++) {
			for (int b = 0; b < 32; b++) {
				if( (bits[w] & (1 << b)) != 0 ) {
					value(w*32+b, src, values);
				}
			}
		}
		out.writeIntArray(bits);
		out.writeOpaque(values.array(), 0, values.size());
	}

	/**
	 * A fattr4 with only rdattr_error, for a READDIR entry whose attributes can't be read.
	 */
	public static void encodeError(int status, XdrEncoder out) {
		out.writeIntArray(bitmap(Nfs4.FATTR4_RDATTR_ERROR));
		out.writeInt(4);
		out.writeInt(status);
	}

	/**
	 * Space on the storage of the export (the same for every file in it): usable by this server,
	 * free, or total, in bytes. 0 when the FileSource can't tell or fails, which is what a client's
	 * df shows for it.
	 */
	private static long space(Source src, int kind) {
		FileSource root = src.export.getRoot();
		try {
			long ret = kind == 0 ? root.getUsableSpace() : kind == 1 ? root.getFreeSpace() : root.getTotalSpace();
			return ret < 0 ? 0 : ret;
		} catch (IOException e) {
			return 0;
		}
	}

	private void value(int attr, Source src, XdrEncoder out) throws IOException {
		switch (attr) {
		case Nfs4.FATTR4_SUPPORTED_ATTRS: {
			int[] sup = SUPPORTED.clone();
			if( src.export.getAcls() == null ) {
				clearBit(sup, Nfs4.FATTR4_ACL);
			}
			out.writeIntArray(sup);
			break;
		}
		case Nfs4.FATTR4_TYPE: out.writeInt(src.linkText() != null ? Nfs4.NF4LNK : src.isDirectory() ? Nfs4.NF4DIR : Nfs4.NF4REG); break;
		case Nfs4.FATTR4_FH_EXPIRE_TYPE: out.writeInt(Nfs4.FH4_PERSISTENT); break;
		case Nfs4.FATTR4_CHANGE: out.writeLong(change(src)); break;
		case Nfs4.FATTR4_SIZE: out.writeLong(src.length()); break;
		case Nfs4.FATTR4_LINK_SUPPORT: out.writeBoolean(false); break;
		case Nfs4.FATTR4_SYMLINK_SUPPORT: out.writeBoolean(true); break;
		case Nfs4.FATTR4_NAMED_ATTR: out.writeBoolean(false); break;
		case Nfs4.FATTR4_FSID:
			out.writeLong(src.export.getFsidMajor());
			out.writeLong(src.export.getFsidMinor());
			break;
		case Nfs4.FATTR4_UNIQUE_HANDLES: out.writeBoolean(true); break;
		case Nfs4.FATTR4_LEASE_TIME: out.writeInt((int) leaseSeconds); break;
		case Nfs4.FATTR4_RDATTR_ERROR: out.writeInt(Nfs4.NFS4_OK); break;
		case Nfs4.FATTR4_ACL: {
			List<Ace> acl = src.export.getAcls() == null ? null : src.export.getAcls().get(src.node.getFileid());
			if( acl == null ) {
				acl = Acls.fromMode(mode(src), src.isDirectory());
			}
			out.writeInt(acl.size());
			for (Ace a : acl) {
				out.writeInt(a.getType());
				out.writeInt(a.getFlags());
				out.writeInt(a.getMask());
				out.writeString(qualify(a.getWho()));
			}
			break;
		}
		case Nfs4.FATTR4_ACLSUPPORT:
			out.writeInt(src.export.getAcls() == null ? 0 : Nfs4.ACL4_SUPPORT_ALLOW_ACL | Nfs4.ACL4_SUPPORT_DENY_ACL);
			break;
		case Nfs4.FATTR4_CANSETTIME: out.writeBoolean(true); break;
		case Nfs4.FATTR4_CASE_INSENSITIVE: out.writeBoolean(false); break;
		case Nfs4.FATTR4_CASE_PRESERVING: out.writeBoolean(true); break;
		case Nfs4.FATTR4_CHOWN_RESTRICTED: out.writeBoolean(true); break;
		case Nfs4.FATTR4_FILEHANDLE: out.writeOpaque(src.export.toHandle(src.node)); break;
		case Nfs4.FATTR4_FILEID: out.writeLong(src.node.getFileid()); break;
		case Nfs4.FATTR4_HIDDEN: out.writeBoolean(src.file.isHidden()); break;
		case Nfs4.FATTR4_HOMOGENEOUS: out.writeBoolean(true); break;
		case Nfs4.FATTR4_MAXFILESIZE: out.writeLong(Long.MAX_VALUE); break;
		case Nfs4.FATTR4_MAXLINK: out.writeInt(1); break;
		case Nfs4.FATTR4_MAXNAME: out.writeInt(Export.MAX_NAME); break;
		case Nfs4.FATTR4_MAXREAD: out.writeLong(maxRead); break;
		case Nfs4.FATTR4_MAXWRITE: out.writeLong(maxWrite); break;
		case Nfs4.FATTR4_MODE: out.writeInt(mode(src)); break;
		case Nfs4.FATTR4_NO_TRUNC: out.writeBoolean(true); break;
		case Nfs4.FATTR4_NUMLINKS: out.writeInt(src.isDirectory() ? 2 : 1); break;
		case Nfs4.FATTR4_OWNER: out.writeString(owner(src.file)); break;
		case Nfs4.FATTR4_OWNER_GROUP: out.writeString(group(src.file)); break;
		case Nfs4.FATTR4_RAWDEV: out.writeInt(0); out.writeInt(0); break;
		// a link's own times can't be read through FileSource, and asking would follow it (and fail for a link to nothing)
		case Nfs4.FATTR4_TIME_ACCESS: time(out, src.linkText() != null ? src.modified() : orModified(src, src.file.lastAccessTime())); break;
		case Nfs4.FATTR4_TIME_CREATE: time(out, src.linkText() != null ? src.modified() : orModified(src, src.file.creationTime())); break;
		case Nfs4.FATTR4_TIME_DELTA: time(out, 1); break;
		case Nfs4.FATTR4_TIME_METADATA: time(out, src.modified()); break;
		case Nfs4.FATTR4_TIME_MODIFY: time(out, src.modified()); break;
		case Nfs4.FATTR4_MOUNTED_ON_FILEID: out.writeLong(src.node.getFileid()); break;
		case Nfs4.FATTR4_SPACE_AVAIL: out.writeLong(space(src, 0)); break;
		case Nfs4.FATTR4_SPACE_FREE: out.writeLong(space(src, 1)); break;
		case Nfs4.FATTR4_SPACE_TOTAL: out.writeLong(space(src, 2)); break;
		default:
			throw new IllegalStateException("attribute "+attr+" is in SUPPORTED but not encoded");
		}
	}

	private static long orModified(Source src, long t) throws IOException {
		return t > 0 ? t : src.modified();
	}

	/**
	 * The change attribute: the modify time, plus the changes made through this server, so a
	 * change within the time's resolution still changes it.
	 */
	private static long change(Source src) throws IOException {
		return src.modified()*1000+(src.node.getChanges() % 1000);
	}

	/** nfstime4 from milliseconds */
	static void time(XdrEncoder out, long millis) {
		out.writeLong(Math.floorDiv(millis, 1000));
		out.writeInt((int) (Math.floorMod(millis, 1000)*1000000));
	}

	static int mode(Source src) {
		FileSource f = src.file;
		int m = 0;
		if( src.linkText() != null ) {
			// a symbolic link has no mode of its own; what counts is the target's
			return 0777;
		}
		try {
			if( f.canOwnerRead() ) m |= Nfs4.MODE4_RUSR;
			if( f.canOwnerWrite() ) m |= Nfs4.MODE4_WUSR;
			if( f.canOwnerExecute() ) m |= Nfs4.MODE4_XUSR;
			if( f.canGroupRead() ) m |= Nfs4.MODE4_RGRP;
			if( f.canGroupWrite() ) m |= Nfs4.MODE4_WGRP;
			if( f.canGroupExecute() ) m |= Nfs4.MODE4_XGRP;
			if( f.canOtherRead() ) m |= Nfs4.MODE4_ROTH;
			if( f.canOtherWrite() ) m |= Nfs4.MODE4_WOTH;
			if( f.canOtherExecute() ) m |= Nfs4.MODE4_XOTH;
		} catch (IOException | RuntimeException e) {
			try {
				m = src.isDirectory() ? 0755 : 0644;
			} catch (IOException e1) {
				m = 0644;
			}
		}
		return m;
	}

	/**
	 * The owner attribute: the uid number when the id map has the owner's name (RFC 7530
	 * section 5.9 allows numbers with AUTH_SYS, so a client shows the uid the server maps to
	 * that user), else name@domain.
	 */
	String owner(FileSource f) {
		try {
			UserPrincipal p = f.getOwner();
			if( p == null ) {
				return qualify(NOBODY);
			}
			Long uid = numbers == null ? null : numbers.uid(p.getName());
			return uid != null ? Long.toString(uid) : qualify(p.getName());
		} catch (IOException | RuntimeException e) {
			return qualify(NOBODY);
		}
	}

	/**
	 * @return true if a SETATTR owner names the file's owner: as the attribute is sent, by name,
	 * or by a uid the id map maps to that name
	 */
	private boolean isOwner(String asked, FileSource f) {
		String name = unqualify(asked);
		if( name.equals(unqualify(owner(f))) ) {
			return true;
		}
		try {
			UserPrincipal p = f.getOwner();
			if( p == null ) {
				return false;
			}
			if( name.equals(p.getName()) ) {
				return true;
			}
			if( numbers != null && !name.isEmpty() && name.chars().allMatch(Character::isDigit) ) {
				return p.getName().equals(numbers.user(Long.parseLong(name)));
			}
		} catch (IOException | RuntimeException e) {
			return false;
		}
		return false;
	}

	String group(FileSource f) {
		try {
			GroupPrincipal p = f.getGroup();
			if( p == null ) {
				return qualify(NOBODY);
			}
			Long gid = numbers == null ? null : numbers.gid(p.getName());
			return gid != null ? Long.toString(gid) : qualify(p.getName());
		} catch (IOException | RuntimeException e) {
			return qualify(NOBODY);
		}
	}

	/** RFC 7530 section 5.9: user@dns_domain, unless the name is a number or already has a domain. */
	String qualify(String name) {
		if( name.indexOf('@') >= 0 || name.chars().allMatch(Character::isDigit) ) {
			return name;
		}
		return name+"@"+domain;
	}

	/** @return the name without @domain when the domain is this server's, else as given */
	String unqualify(String name) {
		String suffix = "@"+domain;
		return name.endsWith(suffix) ? name.substring(0, name.length()-suffix.length()) : name;
	}

	// ------------------------------------------------------------ SETATTR / CREATE / OPEN

	/** The attributes a client asked to set. */
	public static final class Settings {
		/** what was asked for (the attrsset of a successful reply) */
		public int[] bits = new int[0];
		public Long size;
		/** the access control list asked for, null when not set */
		public List<Ace> acl;
		public Integer mode;
		public String owner;
		public String group;
		/** null: not set; -1: the server's time; else ms */
		public Long atime;
		public Long mtime;

		public boolean isEmpty() {
			for (int b : bits) {
				if( b != 0 ) {
					return false;
				}
			}
			return true;
		}
	}

	/**
	 * Read a fattr4 a client sent to set attributes.
	 * @throws Nfs4Exception NFS4ERR_ATTRNOTSUPP for an attribute this server doesn't support,
	 * NFS4ERR_INVAL for one that can't be set, NFS4ERR_BADXDR if the values don't decode
	 */
	public static Settings decodeSettings(XdrDecoder in) throws Nfs4Exception, XdrException {
		int[] bits = in.readIntArray(MAX_BITMAP_WORDS);
		byte[] vals = in.readOpaque(64*1024);
		if( hasOtherThan(bits, SUPPORTED) ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_ATTRNOTSUPP);
		}
		if( hasOtherThan(bits, WRITABLE) ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "attribute can't be set");
		}
		Settings ret = new Settings();
		ret.bits = bits;
		XdrDecoder v = new XdrDecoder(vals);
		try {
			if( isSet(bits, Nfs4.FATTR4_SIZE) ) {
				ret.size = v.readLong();
				if( ret.size < 0 ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_FBIG);
				}
			}
			if( isSet(bits, Nfs4.FATTR4_ACL) ) {
				int n = v.readInt();
				if( n < 0 || n > Acls.MAX_ACES ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, n+" access control entries");
				}
				List<Ace> acl = new ArrayList<Ace>(n);
				for (int i = 0; i < n; i++) {
					int type = v.readInt();
					int flags = v.readInt();
					int mask = v.readInt();
					acl.add(new Ace(type, flags, mask, v.readString(Nfs4.NFS4_OPAQUE_LIMIT)));
				}
				ret.acl = Acls.validate(acl);
			}
			if( isSet(bits, Nfs4.FATTR4_MODE) ) {
				int m = v.readInt();
				if( (m & ~07777) != 0 ) {
					throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "mode "+Integer.toOctalString(m));
				}
				ret.mode = m;
			}
			if( isSet(bits, Nfs4.FATTR4_OWNER) ) {
				ret.owner = v.readString(Nfs4.NFS4_OPAQUE_LIMIT);
			}
			if( isSet(bits, Nfs4.FATTR4_OWNER_GROUP) ) {
				ret.group = v.readString(Nfs4.NFS4_OPAQUE_LIMIT);
			}
			if( isSet(bits, Nfs4.FATTR4_TIME_ACCESS_SET) ) {
				ret.atime = settime(v);
			}
			if( isSet(bits, Nfs4.FATTR4_TIME_MODIFY_SET) ) {
				ret.mtime = settime(v);
			}
		} catch (XdrDecoder.InvalidUtf8Exception e) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "owner isn't UTF-8");
		}
		if( v.remaining() != 0 ) {
			throw new XdrException("attribute values longer than their attributes");
		}
		return ret;
	}

	private static Long settime(XdrDecoder v) throws XdrException, Nfs4Exception {
		int how = v.readInt();
		if( how == Nfs4.SET_TO_SERVER_TIME4 ) {
			return -1L;
		}
		if( how != Nfs4.SET_TO_CLIENT_TIME4 ) {
			throw new XdrException("time_how4 "+how);
		}
		long sec = v.readLong();
		long nsec = v.readUnsignedInt();
		if( nsec >= 1000000000L ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_INVAL, "nseconds "+nsec);
		}
		return sec*1000+nsec/1000000;
	}

	/**
	 * Apply settings other than size (the caller sets the size, through the open stream).
	 * @throws Nfs4Exception NFS4ERR_PERM for an owner or group change (the FileSource keeps its
	 * owner), NFS4ERR_IO if the FileSource refuses a change
	 */
	public void apply(Settings s, FileSource f) throws IOException, Nfs4Exception {
		if( s.owner != null && !isOwner(s.owner, f) ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_PERM, "changing the owner isn't supported");
		}
		if( s.group != null && !unqualify(s.group).equals(unqualify(group(f))) ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_PERM, "changing the group isn't supported");
		}
		if( s.mode != null ) {
			int m = s.mode;
			boolean ok = f.setOwnerReadable((m & Nfs4.MODE4_RUSR) != 0)
					& f.setOwnerWritable((m & Nfs4.MODE4_WUSR) != 0)
					& f.setOwnerExecutable((m & Nfs4.MODE4_XUSR) != 0)
					& f.setGroupReadable((m & Nfs4.MODE4_RGRP) != 0)
					& f.setGroupWritable((m & Nfs4.MODE4_WGRP) != 0)
					& f.setGroupExecutable((m & Nfs4.MODE4_XGRP) != 0)
					& f.setOtherReadable((m & Nfs4.MODE4_ROTH) != 0)
					& f.setOtherWritable((m & Nfs4.MODE4_WOTH) != 0)
					& f.setOtherExecutable((m & Nfs4.MODE4_XOTH) != 0);
			if( !ok ) {
				throw new Nfs4Exception(Nfs4.NFS4ERR_IO, "the file system didn't take mode "+Integer.toOctalString(m));
			}
		}
		long now = System.currentTimeMillis();
		if( s.atime != null && !f.setLastAccessTime(s.atime < 0 ? now : s.atime) ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_IO, "the file system didn't take the access time");
		}
		if( s.mtime != null && !f.setLastModifiedTime(s.mtime < 0 ? now : s.mtime) ) {
			throw new Nfs4Exception(Nfs4.NFS4ERR_IO, "the file system didn't take the modify time");
		}
	}
}
