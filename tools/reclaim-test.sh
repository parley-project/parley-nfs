#!/bin/bash
#
# Times how a real client gets its delegation back after a server restart (D67, the grace period), with one
# command on the machine that runs the server. A Linux VM that has the export mounted opens a file and keeps it
# open (that is what is given a read delegation); you restart the server, the script sees it go down and come
# back by itself, and then asks the VM about the file every 2 seconds until the file answers.
#
#   tools/reclaim-test.sh
#
# The server must run with parley.nfs.delegations=true and a state folder (parley.nfs.stateDir), and must be
# restarted WITHOUT deleting the state folder's delegations.state. After the restart the server answers
# NFS4ERR_GRACE for the file (for 90 s) until its client has reclaimed the delegation, so:
#   answered within a few seconds: the client reclaimed (what should happen)
#   answered only after about 90 s: the client did not reclaim in time (the open question)
#
# Settings (environment): EXPORT_DIR, MOUNT, VM, SERVER_HOST, PORT, CLIENT_EXEC, SERVER_LOG (optional: the
# file the server's log goes to; then the script also counts the GRACE answers and shows the reclaims).

: "${EXPORT_DIR:=/Volumes/Data/nfs-test}"      # the exported folder, on this machine
: "${MOUNT:=/mnt/parley/docs}"                   # the same folder, mounted in the VM
: "${VM:=nfs-test}"                              # the Multipass VM with the Kerberos mount
: "${SERVER_HOST:=127.0.0.1}"                    # where the server listens, seen from here
: "${PORT:=2049}"
: "${CLIENT_EXEC:=multipass exec $VM --}"        # how a command runs in the VM
: "${SERVER_LOG:=}"

NAME=reclaim-test.dat
FILE=$EXPORT_DIR/$NAME
TMP=$(mktemp -d)
HOLDER=""

up() {
	(exec 3<>"/dev/tcp/$SERVER_HOST/$PORT") 2>/dev/null
}

cleanup() {
	[ -n "$HOLDER" ] && kill "$HOLDER" 2>/dev/null
	$CLIENT_EXEC pkill -f "$NAME" 2>/dev/null
	rm -f "$FILE"
	rm -rf "$TMP"
}
trap cleanup EXIT

up || { echo "no server answers on $SERVER_HOST:$PORT: start it first"; exit 2; }

echo "0123456789" > "$FILE" || { echo "can't make $FILE"; exit 2; }
for i in 1 2 3 4 5 6 7 8 9 10; do
	$CLIENT_EXEC test -f "$MOUNT/$NAME" 2>/dev/null && break
	sleep 1
done
$CLIENT_EXEC test -f "$MOUNT/$NAME" 2>/dev/null \
	|| { echo "the VM doesn't see $MOUNT/$NAME: is the export mounted there, and does $VM answer?"; exit 2; }

# the client opens the file for reading and keeps it open
$CLIENT_EXEC sh -c 'exec 3< "$1"; echo OPEN; sleep 900' sh "$MOUNT/$NAME" > "$TMP/holder.out" 2>&1 &
HOLDER=$!
n=50
until grep -q '^OPEN' "$TMP/holder.out" 2>/dev/null; do
	n=$(( n - 1 ))
	[ $n -le 0 ] && { echo "the VM didn't open the file: $(cat "$TMP/holder.out")"; exit 2; }
	sleep 0.2
done
echo "the client has $MOUNT/$NAME open (a read delegation should have been given)."
[ -n "$SERVER_LOG" ] && echo "server log says: $(grep -c 'Gave a read delegation' "$SERVER_LOG") read delegations given so far"

echo
echo ">>> RESTART THE SERVER NOW (keep the state folder). Waiting up to 5 minutes for it to go down and come back."
n=300
while up; do
	n=$(( n - 1 ))
	[ $n -le 0 ] && { echo "the server was not restarted"; exit 2; }
	sleep 1
done
echo "server is down ($(date +%T))"
while ! up; do
	sleep 0.2
done
START=$(date +%s)
echo "server is up again ($(date +%T)); asking the client about the file every 2 s (up to 150 s)"

answered=""
while [ $(( $(date +%s) - START )) -lt 150 ]; do
	t=$(( $(date +%s) - START ))
	# a call stuck in the kernel can't be stopped by `timeout` in the VM, so the wait is done here
	$CLIENT_EXEC stat -c %s "$MOUNT/$NAME" > "$TMP/stat.out" 2>&1 &
	spid=$!
	w=0
	while kill -0 "$spid" 2>/dev/null && [ $w -lt 10 ]; do
		sleep 0.5
		w=$(( w + 1 ))
	done
	if kill -0 "$spid" 2>/dev/null; then
		echo "      $t s after the restart: stat has not answered for 5 s (the call is stuck in the client)"
		kill "$spid" 2>/dev/null
	elif grep -q '^[0-9]' "$TMP/stat.out"; then
		answered=$t
		break
	else
		echo "      $t s after the restart: $(head -1 "$TMP/stat.out")"
	fi
	sleep 2
done

echo
if [ -z "$answered" ]; then
	echo "RESULT: the file did not answer within 150 s of the restart"
else
	echo "RESULT: the file answered $answered s after the server was back"
	if [ "$answered" -le 20 ]; then
		echo "        the client reclaimed its delegation in time"
	elif [ "$answered" -ge 80 ]; then
		echo "        only after the grace period ran out: the client did not reclaim in time"
	else
		echo "        in between: look at the server log"
	fi
fi
echo "the client still has the file open: $($CLIENT_EXEC sh -c 'ls -l /proc/[0-9]*/fd 2>/dev/null | grep -c "$1"' sh "$NAME") descriptor(s)"
if [ -n "$SERVER_LOG" ]; then
	echo "NFS4ERR_GRACE answers in the log: $(grep -c '10013' "$SERVER_LOG")"
	echo "reclaims and delegations in the log:"
	grep -i 'reclaim\|delegation' "$SERVER_LOG" | tail -15 | sed 's/^/      /'
fi
