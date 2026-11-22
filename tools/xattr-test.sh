#!/bin/bash
#
# Checks extended attributes (RFC 8276, D101) with a real Linux client: setfattr and getfattr on a mount made with
# vers=4.2. Run it on the machine that runs the server; the client side runs in a Linux VM through `multipass exec`.
#
#   tools/xattr-test.sh
#
# The server must be running (tools/run-server.sh) with sec including sys (the default), exporting a folder whose file
# system keeps user attributes through Java. If it doesn't, the server says xattr_support false and the first step
# shows "Operation not supported"; that is the server's correct answer, not a failure of the code.
#
# Settings (environment):
#   EXPORT_DIR    the exported folder, on this machine              (default /Volumes/Data/nfs-test)
#   EXPORT_NAME   the export's name; the VM mounts server:/<name>  (default docs)
#   VM            the Multipass VM                                  (default nfs-test)
#   MAC_IP        the server's address as the VM sees it            (default 192.168.252.1)
#   MOUNT         where the VM mounts it                            (default /mnt/sys-xattr)
#   CLIENT_EXEC   how a command runs in the VM                      (default multipass exec $VM --)
#
# The VM needs a kernel of 5.9 or later (the Linux client has had RFC 8276 since then) and the attr package, which
# this script installs when setfattr is missing. The Linux client sends the name without its "user." prefix and the
# Java provider adds it, so a name set here as user.origin is "user.origin" on a Linux server and "origin" on macOS.

: "${EXPORT_DIR:=/Volumes/Data/nfs-test}"
: "${EXPORT_NAME:=docs}"
: "${VM:=nfs-test}"
: "${MAC_IP:=192.168.252.1}"
: "${MOUNT:=/mnt/sys-xattr}"
: "${CLIENT_EXEC:=multipass exec $VM --}"

NAME=xattr-test.dat
DIRNAME=xattr-test.dir
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

ok()   { passed=$((passed+1)); echo "  PASS  $1"; }
bad()  { failed=$((failed+1)); echo "  FAIL  $1"; [ -n "$2" ] && echo "        $2"; return 0; }
skip() { skipped=$((skipped+1)); echo "  SKIP  $1"; }

# expect "what" "wanted" "got"
expect() {
	if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "wanted '$2', got '$3'"; fi
}

# the value of an attribute as the VM sees it, "" when there is none
value() {
	vm getfattr --only-values -n "$1" "$2" 2>/dev/null
}

# the value as this machine's file system has it: Linux says user.<name>, macOS says <name>
here() {
	local name=$1 file=$2
	if command -v xattr >/dev/null 2>&1 && xattr -p "$name" "$file" 2>/dev/null; then return 0; fi
	getfattr --only-values -n "user.$name" "$file" 2>/dev/null
}

cleanup() {
	echo "cleaning up (each step waits at most ${LIMIT})"
	rm -rf "$FILE" "$EXPORT_DIR/$DIRNAME" 2>/dev/null
	vm sudo umount "$MOUNT" >/dev/null 2>&1
	vm sudo rmdir "$MOUNT" >/dev/null 2>&1
}
trap cleanup EXIT

echo "xattr test: $MAC_IP:/$EXPORT_NAME mounted with vers=4.2,sec=sys at $MOUNT in $VM; server side $EXPORT_DIR"

rm -rf "$FILE" "$EXPORT_DIR/$DIRNAME" 2>/dev/null
if ! vm which setfattr >/dev/null 2>&1; then
	echo "installing attr in the VM"
	LIMIT=180 vm sudo apt-get install -y attr >/dev/null 2>&1 || { echo "can't install attr in the VM"; exit 1; }
fi
echo "        VM kernel: $($CLIENT_EXEC uname -r)"
vm sudo mkdir -p "$MOUNT" || { echo "can't make $MOUNT in the VM"; exit 1; }
if ! vm sudo mount -t nfs4 -o vers=4.2,sec=sys "$MAC_IP:/$EXPORT_NAME" "$MOUNT" 2>&1; then
	echo "can't mount $MAC_IP:/$EXPORT_NAME with vers=4.2,sec=sys (is the server running, and does parley.nfs.sec include sys?)"
	exit 1
fi
F="$MOUNT/$NAME"
vm sh -c "touch '$F'" 2>&1 || { echo "can't make a file in the export (does the mapped user have write on it?)"; exit 1; }

echo "1. set and get"
if out=$(vm setfattr -n user.origin -v hello "$F" 2>&1); then
	ok "setfattr user.origin"
else
	bad "setfattr user.origin" "$out"
	case "$out" in
		*"not supported"*) echo "        The server answered xattr_support false: the exported file system keeps no user attributes through Java."
		                   echo "        Export a folder on a file system that does, then run this again." ;;
	esac
	exit 1
fi
expect "getfattr user.origin" "hello" "$(value user.origin "$F")"
expect "this machine's file system has it too" "hello" "$(here origin "$FILE")"

echo "2. a list"
vm setfattr -n user.second -v two "$F" >/dev/null 2>&1
names=$(vm getfattr -d "$F" 2>/dev/null | grep '^user\.' | cut -d= -f1 | sort | tr '\n' ' ')
expect "getfattr -d lists both names" "user.origin user.second " "$names"

echo "3. replace"
vm setfattr -n user.origin -v replaced "$F" >/dev/null 2>&1
expect "a second setfattr replaces the value" "replaced" "$(value user.origin "$F")"

echo "4. binary values and empty values"
vm setfattr -n user.bin -v 0x00ff10 "$F" >/dev/null 2>&1
expect "0x00ff10 comes back as it was" "0x00ff10" "$(vm getfattr -e hex -n user.bin "$F" 2>/dev/null | grep '^user.bin=' | cut -d= -f2)"
vm setfattr -n user.empty -v "" "$F" >/dev/null 2>&1
if vm getfattr -n user.empty "$F" >/dev/null 2>&1; then ok "an empty value is kept"; else bad "an empty value is kept"; fi

echo "5. a name that is not ASCII"
vm setfattr -n 'user.café' -v ünï "$F" >/dev/null 2>&1
expect "user.café comes back" "ünï" "$(value 'user.café' "$F")"

echo "6. remove"
vm setfattr -x user.second "$F" >/dev/null 2>&1
if vm getfattr -n user.second "$F" >/dev/null 2>&1; then bad "setfattr -x removed user.second"; else ok "user.second is gone"; fi
if out=$(vm setfattr -x user.second "$F" 2>&1); then
	bad "removing it again" "it was allowed"
else
	ok "removing a name that isn't there is refused ($(echo "$out" | tail -1))"
fi
if out=$(vm getfattr -n user.nothere "$F" 2>&1); then bad "reading a name that isn't there" "it was allowed"; else ok "reading a name that isn't there is refused"; fi

echo "7. a value that is too big (the server's limit is 64 KiB)"
if out=$(vm python3 -c "import os; os.setxattr('$F','user.big',b'a'*70000)" 2>&1); then
	bad "70000 bytes" "it was allowed"
else
	ok "70000 bytes is refused ($(echo "$out" | tail -1))"
fi
if vm python3 -c "import os; os.setxattr('$F','user.big',b'a'*65536)" >/dev/null 2>&1; then
	ok "65536 bytes is kept"
else
	bad "65536 bytes is kept"
fi

echo "8. a folder keeps them too"
vm sh -c "mkdir -p '$MOUNT/$DIRNAME'" >/dev/null 2>&1
vm setfattr -n user.tag -v folder "$MOUNT/$DIRNAME" >/dev/null 2>&1
expect "user.tag on a folder" "folder" "$(value user.tag "$MOUNT/$DIRNAME")"

echo "9. the other direction: set here, read in the VM"
if command -v xattr >/dev/null 2>&1 && [ "$(uname)" = "Darwin" ]; then
	xattr -w fromhere there "$FILE" 2>/dev/null
else
	setfattr -n user.fromhere -v there "$FILE" 2>/dev/null
fi
sleep 2
expect "an attribute set on this machine is seen by the mount" "there" "$(value user.fromhere "$F")"

echo
echo "passed $passed, failed $failed, skipped $skipped"
[ "$failed" = "0" ]
