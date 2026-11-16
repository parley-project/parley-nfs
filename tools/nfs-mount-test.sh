#!/usr/bin/env bash
#
# Exercise a parley-nfs mount from a Linux client (e.g. a Multipass VM).
#
#   ./nfs-mount-test.sh [mount point]        default /mnt/parley
#
# Run it as a normal user (not with sudo). Everything happens in a new folder under the
# mount, which is removed at the end. Each check prints PASS or FAIL; checks of things the
# server doesn't support yet (links, chown, locks) pass when they fail the expected way.
# The exit status is the number of failures.
#
set -u

MNT=${1:-/mnt/parley}
WORK="$MNT/nfs-test-$$"
LOCAL=$(mktemp -d)
PASSED=0
FAILED=0

pass() { PASSED=$((PASSED+1)); printf '  PASS  %s\n' "$1"; }
fail() { FAILED=$((FAILED+1)); printf '  FAIL  %s\n' "$1"; [ -n "${2:-}" ] && printf '        %s\n' "$2"; }

# check "name" command...    : PASS when the command succeeds
check() {
	local name=$1; shift
	local out
	if out=$("$@" 2>&1); then pass "$name"; else fail "$name" "$out"; fi
}

# refused "name" command...  : PASS when the command fails (not supported by design)
refused() {
	local name=$1; shift
	local out
	if out=$("$@" 2>&1); then fail "$name" "it worked, but the server isn't expected to support it"
	else pass "$name ($(echo "$out" | tail -1 | sed 's/.*: //'))"; fi
}

same() { [ "$1" = "$2" ] || { echo "expected '$2', got '$1'"; return 1; }; }

cleanup() {
	rm -rf "$WORK" "$LOCAL" 2>/dev/null
}
trap cleanup EXIT

echo "== Mount"
if [ "$(id -u)" = 0 ]; then
	echo "  Note: running as root; with a user registry root is the anonymous user or refused."
fi
check "$MNT is an NFS mount" findmnt -t nfs4 "$MNT"
findmnt -t nfs4 -no SOURCE,OPTIONS "$MNT" 2>/dev/null | sed 's/^/        /'
check "make the test folder" mkdir "$WORK"
if [ ! -d "$WORK" ]; then
	echo "Can't make $WORK; stopping."
	exit 1
fi

echo "== Files"
check "write a file"            sh -c "echo 'hello parley' > '$WORK/a.txt'"
check "read it back"            same "$(cat "$WORK/a.txt")" "hello parley"
check "append"                  sh -c "echo 'line 2' >> '$WORK/a.txt' && [ \$(wc -l < '$WORK/a.txt') = 2 ]"
check "overwrite (truncate)"    sh -c "echo new > '$WORK/a.txt' && [ \"\$(cat '$WORK/a.txt')\" = new ]"
check "truncate to 3 bytes"     sh -c "truncate -s 3 '$WORK/a.txt' && [ \$(stat -c %s '$WORK/a.txt') = 3 ]"
check "extend with a hole"      sh -c "truncate -s 1M '$WORK/a.txt' && [ \$(stat -c %s '$WORK/a.txt') = 1048576 ]"
check "empty file"              sh -c ": > '$WORK/empty' && [ \$(stat -c %s '$WORK/empty') = 0 ]"
check "name with spaces/UTF-8"  sh -c "echo x > '$WORK/a b ü.txt' && [ -f '$WORK/a b ü.txt' ]"

echo "== Large file (32 MB)"
dd if=/dev/urandom of="$LOCAL/big" bs=1M count=32 status=none
SUM=$(sha256sum < "$LOCAL/big")
check "copy to the mount"       cp "$LOCAL/big" "$WORK/big"
check "size on the mount"       same "$(stat -c %s "$WORK/big")" 33554432
# O_DIRECT reads go to the server instead of the client's cache
check "same bytes from server"  same "$(dd if="$WORK/big" iflag=direct bs=1M status=none | sha256sum)" "$SUM"
check "copy back"               sh -c "cp '$WORK/big' '$LOCAL/back' && cmp -s '$LOCAL/big' '$LOCAL/back'"
check "write in the middle"     sh -c "printf XYZ | dd of='$WORK/big' bs=1 seek=1000000 conv=notrunc status=none \
                                       && [ \"\$(dd if='$WORK/big' iflag=direct bs=4096 skip=244 count=1 status=none | tail -c +577 | head -c 3)\" = XYZ ]"

echo "== Folders"
check "mkdir -p a/b/c"          mkdir -p "$WORK/d1/d2/d3"
check "file in a deep folder"   sh -c "echo deep > '$WORK/d1/d2/d3/f' && [ \"\$(cat '$WORK/d1/d2/d3/f')\" = deep ]"
check "rmdir a non-empty folder fails" sh -c "! rmdir '$WORK/d1' 2>/dev/null"
check "500 files"               sh -c "for i in \$(seq 1 500); do : > '$WORK/d1/f'\$i; done"
check "ls sees all 500"         same "$(ls "$WORK/d1" | grep -c '^f')" 500
check "find sees the tree"      same "$(find "$WORK/d1" | wc -l)" 504

echo "== Rename and remove"
check "rename a file"           sh -c "mv '$WORK/empty' '$WORK/renamed' && [ -f '$WORK/renamed' ] && [ ! -e '$WORK/empty' ]"
check "rename over a file"      sh -c "echo one > '$WORK/r1' && echo two > '$WORK/r2' && mv '$WORK/r1' '$WORK/r2' \
                                       && [ \"\$(cat '$WORK/r2')\" = one ] && [ ! -e '$WORK/r1' ]"
check "rename a folder"         sh -c "mv '$WORK/d1/d2' '$WORK/moved' && [ -f '$WORK/moved/d3/f' ]"
check "editor-style save"       sh -c "echo v2 > '$WORK/.a.txt.swp' && mv '$WORK/.a.txt.swp' '$WORK/a.txt' \
                                       && [ \"\$(cat '$WORK/a.txt')\" = v2 ]"
check "rm -r"                   sh -c "rm -r '$WORK/d1' && [ ! -e '$WORK/d1' ]"

echo "== Attributes"
check "chmod 600"               sh -c "chmod 600 '$WORK/a.txt' && [ \$(stat -c %a '$WORK/a.txt') = 600 ]"
check "set mtime"               sh -c "touch -d '2020-01-02 03:04:05' '$WORK/a.txt' \
                                       && [ \"\$(date -d @\$(stat -c %Y '$WORK/a.txt') '+%F %T')\" = '2020-01-02 03:04:05' ]"
check "mtime moves on write"    sh -c "echo more >> '$WORK/a.txt' && [ \$(stat -c %Y '$WORK/a.txt') -gt 1600000000 ]"
echo "        owner as the VM sees it: $(stat -c '%U:%G (%u:%g)' "$WORK/a.txt")"

echo "== Not supported (should fail)"
refused "symbolic link"         ln -s a.txt "$WORK/sym"
refused "hard link"             ln "$WORK/a.txt" "$WORK/hard"
refused "chown"                 chown "$(id -u)" "$WORK/a.txt"
refused "fifo"                  mkfifo "$WORK/fifo"
refused "lock (until phase 2)"  flock -n "$WORK/a.txt" true
# the failed lock must not break the file's open state (BAD_SEQID before the pynfs fixes)
check "open after a failed lock" sh -c "echo after >> '$WORK/a.txt' && tail -1 '$WORK/a.txt' | grep -q after"

echo "== Real-world use"
if command -v git > /dev/null; then
	check "git init, add, commit" sh -c "cd '$WORK' && git init -q repo && cd repo && echo x > f \
	                                     && git add f && git -c user.name=t -c user.email=t@t commit -qm test \
	                                     && git log --oneline | grep -q test"
	check "git status is clean"   sh -c "cd '$WORK/repo' && out=\$(git status --porcelain) && [ -z \"\$out\" ]"
fi
# -h copies what symbolic links point at: the server can't make links (D9)
check "tar a tree in"           sh -c "mkdir '$WORK/doc' && tar -h -C /usr/share/doc -cf - bash coreutils 2>/dev/null | tar -C '$WORK/doc' -xf -"
check "the tree is the same"    diff -r /usr/share/doc/bash "$WORK/doc/bash"
check "two writers at once"     sh -c "(for i in \$(seq 1 200); do echo \$i; done > '$WORK/w1') & \
                                       (for i in \$(seq 1 200); do echo \$i; done > '$WORK/w2') & wait; \
                                       [ \$(wc -l < '$WORK/w1') = 200 ] && [ \$(wc -l < '$WORK/w2') = 200 ]"
echo "        df: $(df -h "$MNT" | tail -1)"

echo
echo "$PASSED passed, $FAILED failed"
exit $FAILED
