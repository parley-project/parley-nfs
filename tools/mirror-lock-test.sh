#!/bin/bash
#
# Checks parley.nfs.mirrorLocks (D68) end to end with one command, run on the machine that runs the server:
# the other program (tools/LockProbe.java) runs here, the NFS client side (tools/lock-client.py) runs in a
# Linux VM that has the export mounted, started with `multipass exec`.
#
#   tools/mirror-lock-test.sh [--lease]
#
# The server must be running with parley.nfs.mirrorLocks=true (and, for the lock folder, the same
# parley.files.lockDirectory as LOCKDIR below). --lease also does the lease check: it blocks the VM's
# NFS traffic to the server (port 2049 only) until the lease runs out, about 2.5 minutes.
#
# It makes lockmirror-test.dat and lockmirror-lock-client.py in the exported folder and removes them at the end.
# Settings (environment): EXPORT_DIR, MOUNT, VM, LOCKDIR, MAC_IP, CP, CLIENT_EXEC, STATE_DIR.

: "${EXPORT_DIR:=/Volumes/Data/nfs-test}"      # the exported folder, on this machine
: "${MOUNT:=/mnt/parley/docs}"                   # the same folder, mounted in the VM
: "${VM:=nfs-test}"                              # the Multipass VM with the Kerberos mount
: "${LOCKDIR:=/Volumes/Data/nfs-locks}"          # parley.files.lockDirectory of the server
: "${MAC_IP:=192.168.252.1}"                     # the server's address as the VM sees it
M2=$HOME/.m2/repository/us/bringardner/parley
: "${CP:=$M2/parley-files/2.0.0-SNAPSHOT/parley-files-2.0.0-SNAPSHOT.jar:$M2/parley-core/2.0.0-SNAPSHOT/parley-core-2.0.0-SNAPSHOT.jar:$M2/parley-io/2.0.0-SNAPSHOT/parley-io-2.0.0-SNAPSHOT.jar}"
: "${STATE_DIR:=/Volumes/Data/nfs-state}"       # parley.nfs.stateDir of the server (F: is a delegation given?)
: "${CLIENT_EXEC:=multipass exec $VM --}"        # how a command runs in the VM

LEASE=false
[ "$1" = "--lease" ] && LEASE=true

REPO=$(cd "$(dirname "$0")/.." && pwd)
NAME=lockmirror-test.dat
CLIENT_SCRIPT=lockmirror-lock-client.py
FILE=$EXPORT_DIR/$NAME
TMP=$(mktemp -d)
PIDS=""
BLOCKED=false
passed=0
failed=0

probe() {
	java -Dparley.files.lockDirectory="$LOCKDIR" -cp "$CP" "$REPO/tools/LockProbe.java" "$@" 2>/dev/null
}

# GOT, NOT or ERR: can the other program lock the region of the file now?
try_probe() {
	probe try "$FILE" "$@" > /dev/null
	case $? in
		0) echo GOT ;;
		1) echo NOT ;;
		*) echo ERR ;;
	esac
}

client_py() {
	$CLIENT_EXEC python3 "$MOUNT/$CLIENT_SCRIPT" "$@"
}

# wait_for FILE PATTERN SECONDS
wait_for() {
	local n=$(( $3 * 5 ))
	while [ $n -gt 0 ]; do
		grep -q "$2" "$1" 2>/dev/null && return 0
		sleep 0.2
		n=$(( n - 1 ))
	done
	return 1
}

# start_client_lock LABEL POSITION LENGTH SECONDS [r|w]: the client takes the lock and holds it in the background
start_client_lock() {
	client_py lock "$MOUNT/$NAME" "$2" "$3" "${5:-w}" "$4" > "$TMP/$1.out" 2>&1 &
	PIDS="$PIDS $!"
	LAST_PID=$!
	# "GOT ..." or "NOT GOT ..." at the start of a line: 0 if the client holds the lock
	wait_for "$TMP/$1.out" "^\(NOT \)\{0,1\}GOT" 30 || { echo "      client $1: no answer: $(cat "$TMP/$1.out")"; return 1; }
	if grep -q "^NOT GOT" "$TMP/$1.out"; then
		echo "      client $1 was refused: $(head -1 "$TMP/$1.out")"
		return 2
	fi
	return 0
}

# client_lock_try POSITION LENGTH: GOT or NOT, the client's answer for a lock it doesn't hold on
client_lock_try() {
	local out
	out=$(client_py lock "$MOUNT/$NAME" "$1" "$2" w 1 2>&1)
	case $out in
		NOT\ GOT*) echo NOT ;;
		GOT*) echo GOT ;;
		*) echo "ERR($out)" ;;
	esac
}

# what holds the lock files right now (the check that failed ran while the lock was still held)
diagnose() {
	echo "      --- lock files in $LOCKDIR:"
	ls -la "$LOCKDIR" 2>&1 | sed 's/^/      /'
	echo "      --- programs holding them (lsof):"
	lsof +D "$LOCKDIR" 2>&1 | sed 's/^/      /'
	echo "      --- the client's lock program in $VM:"
	$CLIENT_EXEC pgrep -fl "$CLIENT_SCRIPT" 2>&1 | sed 's/^/      /'
}

check() {
	if [ "$2" = "$3" ]; then
		echo "PASS  $1"
		passed=$(( passed + 1 ))
	else
		echo "FAIL  $1: expected $2, got $3"
		failed=$(( failed + 1 ))
		diagnose
	fi
}

cleanup() {
	exec 9>&- 2>/dev/null
	for p in $PIDS $HOLD_PID; do
		kill "$p" 2>/dev/null
	done
	if $BLOCKED; then
		$CLIENT_EXEC sudo iptables -D OUTPUT -d "$MAC_IP" -p tcp --dport 2049 -j DROP 2>/dev/null
	fi
	$CLIENT_EXEC pkill -f "$CLIENT_SCRIPT" 2>/dev/null
	rm -f "$FILE" "$EXPORT_DIR/$CLIENT_SCRIPT"
	rm -rf "$TMP"
}
trap cleanup EXIT

echo "mirror-lock-test: $FILE, seen in $VM as $MOUNT/$NAME"
echo "0123456789" > "$FILE" || { echo "can't make $FILE"; exit 2; }
cp "$REPO/tools/lock-client.py" "$EXPORT_DIR/$CLIENT_SCRIPT" || { echo "can't copy lock-client.py"; exit 2; }
# the VM sees the new files after its attribute cache times out
for i in 1 2 3 4 5 6 7 8 9 10; do
	$CLIENT_EXEC test -f "$MOUNT/$NAME" -a -f "$MOUNT/$CLIENT_SCRIPT" 2>/dev/null && break
	sleep 1
done
$CLIENT_EXEC test -f "$MOUNT/$NAME" -a -f "$MOUNT/$CLIENT_SCRIPT" 2>/dev/null \
	|| { echo "the VM doesn't see $MOUNT/$NAME: is the export mounted there, and does $VM answer?"; exit 2; }
check "the other program can lock a file nobody has locked" GOT "$(try_probe 0 0)"

# A: the client's lock is seen by the other program
start_client_lock A 0 0 25
check "A1 client holds a lock" 0 $?
check "A2 other program is refused while the client holds it" NOT "$(try_probe 0 0)"
wait "$LAST_PID" 2>/dev/null
check "A3 other program gets it after the client lets go" GOT "$(try_probe 0 0)"

# B and C: the other program's lock is seen by the client, by a lock and by a test
mkfifo "$TMP/hold.fifo"
probe hold "$FILE" 0 0 < "$TMP/hold.fifo" > "$TMP/hold.out" &
HOLD_PID=$!
exec 9> "$TMP/hold.fifo"
wait_for "$TMP/hold.out" "^GOT" 30
check "B1 other program holds a lock" 0 $?
check "B2 client is refused while the other program holds it" NOT "$(client_lock_try 0 0)"
out=$(client_py test "$MOUNT/$NAME" 0 0 w 2>&1)
case $out in
	CONFLICT*) got=CONFLICT ;;
	FREE*) got=FREE ;;
	*) got="ERR($out)" ;;
esac
check "C1 client's LOCKT shows the other program's lock" CONFLICT "$got"
echo >&9
exec 9>&-
wait "$HOLD_PID" 2>/dev/null
HOLD_PID=""
check "B3 client gets the lock after the other program lets go" GOT "$(client_lock_try 0 0)"

# D: a part of the file
start_client_lock D 0 100 25
check "D1 client holds 0 to 100" 0 $?
check "D2 other program refused inside it (50 +10)" NOT "$(try_probe 50 10)"
check "D3 other program refused at its last byte (99 +1)" NOT "$(try_probe 99 1)"
check "D4 other program gets the part after it (100 +10)" GOT "$(try_probe 100 10)"
check "D5 other program gets the part far after it (150 +10)" GOT "$(try_probe 150 10)"
wait "$LAST_PID" 2>/dev/null
check "D6 other program gets it all after the client lets go" GOT "$(try_probe 0 0)"

# F: a client with a read delegation may take its shared locks itself and never tell the server (needs
# parley.nfs.delegations=true and a stateDir; skipped when the server gave the client no delegation)
$CLIENT_EXEC sh -c 'cat "$1" > /dev/null' sh "$MOUNT/$NAME" 2>&1 | head -3
start_client_lock F 0 0 25 r
check "F1 client holds a shared lock on a file it has open for reading" 0 $?
n=10
while [ $n -gt 0 ] && ! grep -q "$NAME" "$STATE_DIR/delegations.state" 2>/dev/null; do
	n=$(( n - 1 ))
	sleep 0.5
done
if grep -q "$NAME" "$STATE_DIR/delegations.state" 2>/dev/null; then
	echo "      the client holds a read delegation of the file"
	check "F2 other program is refused an exclusive lock while the client holds a shared one" NOT "$(try_probe 0 0)"
else
	echo "skip  F2: no delegation of the file in $STATE_DIR/delegations.state (parley.nfs.delegations off, or none given)"
fi
wait "$LAST_PID" 2>/dev/null

# E: a lock goes with its client's lease
if $LEASE; then
	start_client_lock E 0 0 400
	check "E1 client holds a lock" 0 $?
	check "E2 other program is refused" NOT "$(try_probe 0 0)"
	echo "      blocking $VM's traffic to $MAC_IP port 2049; waiting for the lease (about 90s)"
	$CLIENT_EXEC sudo iptables -A OUTPUT -d "$MAC_IP" -p tcp --dport 2049 -j DROP && BLOCKED=true
	got=NOT
	waited=0
	while [ $waited -lt 180 ]; do
		sleep 5
		waited=$(( waited + 5 ))
		got=$(try_probe 0 0)
		[ "$got" = GOT ] && break
	done
	echo "      the other program got it after about ${waited}s"
	check "E3 other program gets the lock when the client's lease runs out" GOT "$got"
	$CLIENT_EXEC sudo iptables -D OUTPUT -d "$MAC_IP" -p tcp --dport 2049 -j DROP && BLOCKED=false
else
	echo "skip  E lease expiry (run with --lease)"
fi

echo
echo "passed $passed, failed $failed"
[ $failed -eq 0 ]
