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
#   TARGET_USER   a user this machine has, to give the file to      (default nobody)
#   TARGET_GROUP  a group this machine has, and the VM has too      (default staff on a Mac; the VM must know it by name,
#                 so the group check is skipped when the VM doesn't)
#   CLIENT_EXEC   how a command runs in the VM                      (default multipass exec $VM --)

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

vm() { $CLIENT_EXEC "$@"; }

# the owner and group of the file as this machine's operating system sees them
owner_here() { stat -f %Su "$FILE" 2>/dev/null || stat -c %U "$FILE"; }
group_here() { stat -f %Sg "$FILE" 2>/dev/null || stat -c %G "$FILE"; }

ok()   { passed=$((passed+1)); echo "  PASS  $1"; }
bad()  { failed=$((failed+1)); echo "  FAIL  $1"; [ -n "$2" ] && echo "        $2"; }
skip() { skipped=$((skipped+1)); echo "  SKIP  $1"; }

# expect_refused "what" "command in the VM" : the command must fail; its message is shown
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
	vm sudo rm -f "$MOUNT/$NAME" >/dev/null 2>&1
	vm sudo umount "$MOUNT" >/dev/null 2>&1
	vm sudo rmdir "$MOUNT" >/dev/null 2>&1
}
trap cleanup EXIT

echo "chown test: $MAC_IP:/$EXPORT_NAME mounted with sec=sys at $MOUNT in $VM; server side $EXPORT_DIR"
$UNMAPPED && echo "(--unmapped: the id map has no uid.0 line, so root is expected to be refused)"

if [ "$(id -u)" != "0" ] && ! pgrep -u 0 -f "us.bringardner.parley.nfs.server.NfsServer" >/dev/null 2>&1; then
	echo "  note: no server running as root was found by this script; it must be, or the operating system refuses the change"
fi

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
before=$(owner_here)
echo "        owner here: $before, group $(group_here)"

echo "2. an ordinary user may not change the owner (the client refuses it before it reaches the server)"
expect_refused "chown $TARGET_USER as the VM's user" "chown $TARGET_USER '$MOUNT/$NAME'"
[ "$(owner_here)" = "$before" ] && ok "the owner is still $before" || bad "the owner changed to $(owner_here)"

echo "3. root"
if $UNMAPPED; then
	expect_refused "sudo chown $TARGET_USER with uid 0 not mapped" "sudo chown $TARGET_USER '$MOUNT/$NAME'"
	[ "$(owner_here)" = "$before" ] && ok "the owner is still $before" || bad "the owner changed to $(owner_here)"
else
	if out=$(vm sudo chown "$TARGET_USER" "$MOUNT/$NAME" 2>&1); then
		if [ "$(owner_here)" = "$TARGET_USER" ]; then
			ok "sudo chown $TARGET_USER: the owner here is now $TARGET_USER"
		else
			bad "sudo chown $TARGET_USER said yes but the owner here is $(owner_here)"
		fi
	else
		bad "sudo chown $TARGET_USER" "$out; check the server log: is uid.0 in the id map, does that user have write on the export, is the server running as root, does this machine have a user $TARGET_USER?"
	fi

	if vm getent group "$TARGET_GROUP" >/dev/null 2>&1; then
		if out=$(vm sudo chown ":$TARGET_GROUP" "$MOUNT/$NAME" 2>&1); then
			[ "$(group_here)" = "$TARGET_GROUP" ] && ok "sudo chown :$TARGET_GROUP: the group here is now $TARGET_GROUP" \
				|| bad "sudo chown :$TARGET_GROUP said yes but the group here is $(group_here)"
		else
			bad "sudo chown :$TARGET_GROUP" "$out"
		fi
	else
		skip "the group check: the VM has no group $TARGET_GROUP (set TARGET_GROUP to one both have)"
	fi

	echo "4. a name nobody has, and a number nobody maps (D75: a number goes to the operating system as a uid)"
	expect_refused "sudo chown nosuchuser" "sudo chown nosuchuser '$MOUNT/$NAME'"
	if vm sudo chown 12345 "$MOUNT/$NAME" >/dev/null 2>&1; then
		[ "$(owner_here)" = "12345" ] && ok "sudo chown 12345: the owner here is the uid 12345" \
			|| bad "sudo chown 12345 said yes but the owner here is $(owner_here)"
		vm sudo chown "$TARGET_USER" "$MOUNT/$NAME" >/dev/null 2>&1
	else
		bad "sudo chown 12345" "refused; a server running as root hands a number to the operating system as a uid"
	fi
	echo "5. a file's owner is shown as the operating system's uid when no id map names it"
	own=$(vm stat -c %u "$MOUNT/$NAME" 2>/dev/null)
	echo "        the VM sees uid $own for the owner $(owner_here) here"
fi

echo
echo "passed $passed, failed $failed, skipped $skipped"
[ $failed -eq 0 ]
