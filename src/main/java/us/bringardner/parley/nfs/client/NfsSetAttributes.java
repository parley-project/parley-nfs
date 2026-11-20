package us.bringardner.parley.nfs.client;

import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.XdrEncoder;

/**
 * The attributes of a file to change with {@link NfsClient#setAttributes}: only what is set is sent.
 * Owners and groups are the strings of NFSv4 ("name@domain" or a number as text).
 */
public final class NfsSetAttributes {

	private int mode = -1;
	private String owner;
	private String group;
	private long accessTime = -1;
	private long modifyTime = -1;

	/** @param mode the permission bits (0 to 07777) */
	public NfsSetAttributes mode(int mode) {
		this.mode = mode & 07777;
		return this;
	}

	public NfsSetAttributes owner(String owner) {
		this.owner = owner;
		return this;
	}

	public NfsSetAttributes group(String group) {
		this.group = group;
		return this;
	}

	/** @param millis the access time, in milliseconds since 1970 */
	public NfsSetAttributes accessTime(long millis) {
		this.accessTime = millis;
		return this;
	}

	/** @param millis the modify time, in milliseconds since 1970 */
	public NfsSetAttributes modifyTime(long millis) {
		this.modifyTime = millis;
		return this;
	}

	boolean isEmpty() {
		return mode < 0 && owner == null && group == null && accessTime < 0 && modifyTime < 0;
	}

	/** @return the bitmap of what is set */
	int[] bitmap() {
		int[] map = new int[2];
		set(map, mode >= 0, Nfs4.FATTR4_MODE);
		set(map, owner != null, Nfs4.FATTR4_OWNER);
		set(map, group != null, Nfs4.FATTR4_OWNER_GROUP);
		set(map, accessTime >= 0, Nfs4.FATTR4_TIME_ACCESS_SET);
		set(map, modifyTime >= 0, Nfs4.FATTR4_TIME_MODIFY_SET);
		return map;
	}

	private static void set(int[] map, boolean on, int bit) {
		if( on ) {
			map[bit/32] |= 1 << (bit%32);
		}
	}

	/** @return the values, in the order of the attributes' numbers */
	byte[] values() {
		XdrEncoder e = new XdrEncoder();
		if( mode >= 0 ) {
			e.writeInt(mode);
		}
		if( owner != null ) {
			e.writeString(owner);
		}
		if( group != null ) {
			e.writeString(group);
		}
		if( accessTime >= 0 ) {
			time(e, accessTime);
		}
		if( modifyTime >= 0 ) {
			time(e, modifyTime);
		}
		return e.toByteArray();
	}

	/** settime4: set to the client's time, then nfstime4 */
	private static void time(XdrEncoder e, long millis) {
		e.writeInt(1).writeLong(Math.floorDiv(millis, 1000L)).writeUnsignedInt(Math.floorMod(millis, 1000L)*1000000L);
	}
}
