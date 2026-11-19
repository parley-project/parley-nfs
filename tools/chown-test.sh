#!/bin/bash
#
# Checks that only the mapped root can change the owner and group of a file (D74), with a real Linux client.
# Run it on the machine that runs the server; the client side runs in a Linux VM through `multipass exec`.
#
#   tools/chown-test.sh [--unmapped]
#
# The server must be running as root, with an id map that has the line uid.0=<registry user> (the user who may
# write the export), and with sec including sys (the default). This script mounts the export a second time in the
# VM with sec=sys, because a Kerberos mount has no uid 0 to test with, makes one file in it, and removes it at the end.
#
# --unmapped: run it against a server whose id map has NO uid.0 line; then every chown is expected to be refused.
#
# Settings (environment):
#   EXPORT_DIR    the exported folder, on this machine              (default /Volumes/Data/nfs-test)
#   EXPORT_NAME   the export's name; the VM mounts server:/<name>  (default docs)
#   VM            the Multipass VM                                  (default nfs-test)
#   MAC_IP        the server's address as the VM sees it            (default 192.168.252.1)
#   MOUNT         where the VM mounts it                            (default /mnt/sys-chown)
#   CLIENT_EXEC   how a command runs in the VM                      (default multipass exec $VM --)
#   TARGET_USER   a user the VM has, to give the file to            (default nobody)
#   TARGET_GROUP  a group the VM has                                (default staff; the group check is skipped without it)
#
# With sec=sys a Linux client sends the NUMBER of the user (the VM's uid for TARGET_USER), not its name, so what this
# machine must show afterwards is that same number; this machine needs no user of that name (D75).

: "${EXPORT_DIR:=/Volumes/Data/nfs-test}"
: "${EXPORT_NAME:=docs}"
: "${VM:=nfs-test}"
: "${MAC_IP:=192.168.252.1}"
: "${MOUNT:=/mnt/sys-chown}"
: "${TARGET_USER:=nobody}"
: "${TARGET_GROUP:=staff}"
: "${CLIENT_EXEC:=multipass exec $VM --}"

UNMAPPED=false
[ "$1" = "--unmapped" ] && UNMAPPED=true

NAME=chown-test.dat
FILE=$EXPORT_DIR/$NAME
passed=0
failed=0
skipped=0
LIMIT=${LIMIT:-30}                               # seconds a command in the VM may take

# a command in the VM, shown first, and cut off after LIMIT seconds so that nothing waits for ever
vm() {
	echo "        \$ $*" >&2
	$CLIENT_EXEC timeout "$LIMIT" "$@"
}

# the uid and gid of the file as this machine's operating system has them
uid_here() { stat -c %u "$FILE" 2>/dev/null || stat -f %u "$FILE"; }
gid_here() { stat -c %g "$FILE" 2>/dev/null || stat -f %g "$FILE"; }

ok()   { passed=$((passed+1)); echo "  PASS  $1"; }
bad()  { failed=$((failed+1)); echo "  FAIL  $1"; [ -n "$2" ] && echo "        $2"; return 0; }
skip() { skipped=$((skipped+1)); echo "  SKIP  $1"; }

# expect_refused "what" command... : the command must fail in the VM; its message is shown
expect_refused() {
	local what=$1; shift
	local out
	if out=$(vm sh -c "$*" 2>&1); then
		bad "$what" "it was allowed: $out"
	else
		ok "$what ($(echo "$out" | tail -1))"
	fi
}

cleanup() {
	echo "cleaning up (each step waits at most ${LIMIT})"
	# removed on this machine: a file given to another owner can't always be removed through the mount
	rm -f "$FILE" 2>/dev/null
	vm sudo umount "$MOUNT" >/dev/null 2>&1
	vm sudo rmdir "$MOUNT" >/dev/null 2>&1
}
trap cleanup EXIT

echo "chown test: $MAC_IP:/$EXPORT_NAME mounted with sec=sys at $MOUNT in $VM; server side $EXPORT_DIR"
$UNMAPPED && echo "(--unmapped: the registry has no user for uid 0, so root is expected to be refused)"

if [ "$(id -u)" != "0" ] && ! pgrep -u 0 -f "us.bringardner.parley.nfs.server.NfsServer" >/dev/null 2>&1; then
	echo "  note: no server running as root was found by this script; it must be, or the operating system refuses the change"
fi

# a file left by an earlier run (owned by another user, so the mount may not let its owner touch it)
rm -f "$FILE" 2>/dev/null
vm sudo mkdir -p "$MOUNT" || { echo "can't make $MOUNT in the VM"; exit 1; }
if ! vm sudo mount -t nfs4 -o vers=4.0,sec=sys "$MAC_IP:/$EXPORT_NAME" "$MOUNT" 2>&1; then
	echo "can't mount $MAC_IP:/$EXPORT_NAME with sec=sys (is the server running, and does parley.nfs.sec include sys?)"
	exit 1
fi

echo "1. a file made by an ordinary user"
if vm sh -c "touch '$MOUNT/$NAME'" 2>&1; then
	ok "touch as the VM's user"
else
	bad "touch as the VM's user" "the mapped user may not write the export, or the folder isn't writable"
	exit 1
fi
before_uid=$(uid_here)
before_gid=$(gid_here)
echo "        owner here: uid $before_uid, gid $before_gid"

want_uid=$(vm id -u "$TARGET_USER" 2>/dev/null)
want_gid=$(vm getent group "$TARGET_GROUP" 2>/dev/null | cut -d: -f3)
if [ -z "$want_uid" ]; then
	echo "the VM has no user $TARGET_USER (set TARGET_USER)"; exit 1
fi

echo "2. an ordinary user may not change the owner (the server refuses it: SETATTR with NFS4ERR_ACCESS, shown in its log)"
expect_refused "chown $TARGET_USER as the VM's user" "chown $TARGET_USER '$MOUNT/$NAME'"
[ "$(uid_here)" = "$before_uid" ] && ok "the owner is still uid $before_uid" || bad "the owner is now uid $(uid_here)"

echo "3. root"
if $UNMAPPED; then
	expect_refused "sudo chown $TARGET_USER with uid 0 not mapped" "sudo chown $TARGET_USER '$MOUNT/$NAME'"
	[ "$(uid_here)" = "$before_uid" ] && ok "the owner is still uid $before_uid" || bad "the owner is now uid $(uid_here)"
else
	if out=$(vm sudo chown "$TARGET_USER" "$MOUNT/$NAME" 2>&1); then
		if [ "$(uid_here)" = "$want_uid" ]; then
			ok "sudo chown $TARGET_USER: the owner here is now uid $want_uid, the VM's uid for $TARGET_USER"
		else
			bad "sudo chown $TARGET_USER said yes" "the owner here is uid $(uid_here), expected $want_uid"
		fi
	else
		bad "sudo chown $TARGET_USER" "$out; check the server log: is uid 0 mapped to a registry user with write on the export, and is the server running as root?"
	fi

	if [ -n "$want_gid" ]; then
		if out=$(vm sudo chown ":$TARGET_GROUP" "$MOUNT/$NAME" 2>&1); then
			[ "$(gid_here)" = "$want_gid" ] && ok "sudo chown :$TARGET_GROUP: the group here is now gid $want_gid" \
				|| bad "sudo chown :$TARGET_GROUP said yes" "the group here is gid $(gid_here), expected $want_gid"
		else
			bad "sudo chown :$TARGET_GROUP" "$out"
		fi
	else
		skip "the group check: the VM has no group $TARGET_GROUP (set TARGET_GROUP)"
	fi

	echo "4. a name nobody has, and a number nobody maps (D75: a number goes to the operating system as a uid)"
	expect_refused "sudo chown nosuchuser" "sudo chown nosuchuser '$MOUNT/$NAME'"
	if vm sudo chown 12345 "$MOUNT/$NAME" >/dev/null 2>&1; then
		[ "$(uid_here)" = "12345" ] && ok "sudo chown 12345: the owner here is uid 12345" \
			|| bad "sudo chown 12345 said yes" "the owner here is uid $(uid_here)"
	else
		bad "sudo chown 12345" "refused; a server running as root hands a number to the operating system as a uid"
	fi
fi

echo
echo "passed $passed, failed $failed, skipped $skipped"
[ $failed -eq 0 ]
