#!/usr/bin/env bash
#
# Exercise a parley-nfs mount from a Linux client (e.g. a Multipass VM).
#
#   ./nfs-mount-test.sh [mount point]        default /mnt/parley
#   READ_UID=1001 DENIED_UID=1002 ./nfs-mount-test.sh
#
# With a user registry (docs: "Who may do what"), READ_UID is a uid the registry lets read the
# export but not write it, and DENIED_UID one it refuses; each adds checks run with
# sudo -n -u '#uid' (so the VM needs passwordless sudo, and no local user for the uid).
# Run it as a normal user (not with sudo). Everything happens in a new folder under the
# mount, which is removed at the end. Each check prints PASS or FAIL; checks of things the
# server doesn't support (links, chown, fifos) pass when they fail with the message Linux
# shows for the error the server answers (never "Unknown error 524").
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

# refused_with "name" "message" command...  : PASS when the command fails with that message
refused_with() {
	local name=$1 message=$2; shift 2
	local out
	if out=$("$@" 2>&1); then fail "$name" "it worked, but the server isn't expected to support it"
	elif echo "$out" | grep -q "$message"; then pass "$name ($message)"
	else fail "$name" "expected '$message', got: $(echo "$out" | tail -1)"; fi
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
refused_with "symbolic link"    "Operation not permitted"      ln -s a.txt "$WORK/sym"
refused_with "hard link"        "Invalid cross-device link"    ln "$WORK/a.txt" "$WORK/hard"
# root on the client may ask; the server keeps the owner (NFS4ERR_PERM)
refused "chown (as root)"       sudo -n chown 0 "$WORK/a.txt"
refused_with "fifo"             "Operation not permitted"      mkfifo "$WORK/fifo"

echo "== Space (df)"
# the space is the export's FileSource's own; one that can't tell shows 0 (a local folder can)
check "df shows the total size" sh -c "[ \$(df -B1 --output=size '$MNT' | tail -1 | tr -d ' ') -gt 0 ]"
check "df shows free space"     sh -c "[ \$(df -B1 --output=avail '$MNT' | tail -1 | tr -d ' ') -gt 0 ]"

echo "== Other users (a user registry)"
if [ -z "${READ_UID:-}${DENIED_UID:-}" ]; then
	echo "  (skipped: set READ_UID and/or DENIED_UID)"
elif ! sudo -n true 2>/dev/null; then
	echo "  (skipped: needs passwordless sudo)"
else
	# open the folder to everyone, so a refusal below is the registry's, not the file mode's
	check "set up: a shared folder and file" sh -c "echo shared > '$WORK/shared.txt' && chmod 777 '$WORK' && chmod 666 '$WORK/shared.txt'"
	if [ -n "${READ_UID:-}" ]; then
		as_reader() { sudo -n -u "#$READ_UID" "$@" </dev/null; }
		check "uid $READ_UID can list the folder"  as_reader ls "$WORK"
		check "uid $READ_UID can read a file"      sh -c "[ \"\$(sudo -n -u '#$READ_UID' cat '$WORK/shared.txt' </dev/null)\" = shared ]"
		refused_with "uid $READ_UID can't write a file" "Permission denied" as_reader sh -c "echo x > '$WORK/by-reader.txt'"
		refused_with "uid $READ_UID can't change a file" "Permission denied" as_reader sh -c "echo x >> '$WORK/shared.txt'"
		refused_with "uid $READ_UID can't make a folder" "Permission denied" as_reader mkdir "$WORK/by-reader"
		refused_with "uid $READ_UID can't remove a file" "Permission denied" as_reader rm -f "$WORK/shared.txt"
		check "the file is as it was"             sh -c "[ \"\$(cat '$WORK/shared.txt')\" = shared ]"
	fi
	if [ -n "${DENIED_UID:-}" ]; then
		as_denied() { sudo -n -u "#$DENIED_UID" "$@" </dev/null; }
		refused_with "uid $DENIED_UID can't list the folder" "Permission denied" as_denied ls "$WORK"
		refused_with "uid $DENIED_UID can't read a file"     "Permission denied" as_denied cat "$WORK/shared.txt"
		refused_with "uid $DENIED_UID can't write a file"    "Permission denied" as_denied sh -c "echo x > '$WORK/by-denied.txt'"
	fi
fi

echo "== Locks"
L="$WORK/lockfile"
: > "$L"
check "flock a file"            flock -n "$L" true
flock "$L" sleep 3 &
HOLDER=$!
sleep 1
check "a held lock blocks others" sh -c "! flock -n '$L' true"
wait $HOLDER
check "free once its holder ends" flock -n "$L" true
# -o: only flock holds the lock (not the sleep it runs), so killing flock is killing the holder
flock -o "$L" sleep 60 &
HOLDER=$!
sleep 1
CHILD=$(pgrep -P $HOLDER)
kill -9 $HOLDER
wait $HOLDER 2>/dev/null
[ -n "$CHILD" ] && kill $CHILD 2>/dev/null
check "free once its holder is killed" flock -n "$L" true
if command -v python3 > /dev/null; then
	# byte ranges with fcntl: one process holds bytes 0-9, another can take 20-29 but not 5
	python3 - "$L" > /dev/null 2>&1 <<'PY' &
import fcntl, sys, time
f = open(sys.argv[1], "r+")
fcntl.lockf(f, fcntl.LOCK_EX, 10, 0)
time.sleep(3)
PY
	HOLDER=$!
	sleep 1
	check "fcntl: an overlapping range is refused" python3 -c "
import fcntl, sys
f = open('$L', 'r+')
try:
    fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB, 1, 5)
except OSError:
    sys.exit(0)
sys.exit(1)"
	check "fcntl: another range is free" python3 -c "
import fcntl
f = open('$L', 'r+')
fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB, 10, 20)"
	wait $HOLDER
	check "sqlite database"     python3 -c "
import sqlite3
db = sqlite3.connect('$WORK/test.db')
db.execute('create table t (n integer)')
db.executemany('insert into t values (?)', [(i,) for i in range(1000)])
db.commit()
assert db.execute('select count(*), sum(n) from t').fetchone() == (1000, 499500)
db.close()"
fi

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
