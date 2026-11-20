#!/bin/bash
#
# Tests the generic NFS client (NfsClient, D77) against the Linux kernel's NFS server in a Multipass VM.
# Run it on the machine that has maven and this repo; the server side runs in the VM through `multipass exec`.
#
#   tools/client-live-test.sh            set up the VM's server (if needed) and run NfsClientLiveTest against it
#   tools/client-live-test.sh --remove   stop exporting the folder the script made (the folder stays)
#
# The VM gets the nfs-kernel-server package (if it hasn't it) and the folder /srv/parley-client-test, owned by uid 1000,
# exported as the NFSv4 root (fsid=0), so the client mounts "/". The export says "insecure": the kernel server refuses
# (NFS4ERR_PERM) a client that does not send from a port below 1024, which a Java client does not. The client says it is uid/gid 1000 (AUTH_SYS).
# The test makes a folder of its own in the export and removes it at the end.
#
# Settings (environment):
#   VM          the Multipass VM          (default nfs-client2, the AUTH_SYS one)
#   SERVER_IP   the VM's address          (default: asked of the VM)
#   FOLDER      the folder it exports     (default /srv/parley-client-test)
#   CLIENT_UID  the client's uid and gid  (default 1000)
#   MVN         the maven command         (default mvn)

: "${VM:=nfs-client2}"
: "${FOLDER:=/srv/parley-client-test}"
: "${CLIENT_UID:=1000}"
: "${MVN:=mvn}"
EXPORTS_FILE=/etc/exports.d/parley-client-test.exports

LIMIT=${LIMIT:-60}                               # seconds a command in the VM may take (installing the server: INSTALL_LIMIT)
: "${INSTALL_LIMIT:=600}"

# a command in the VM, shown first, and cut off after LIMIT seconds so that nothing waits for ever
vm() {
	echo "        \$ $*" >&2
	multipass exec "$VM" -- timeout "$LIMIT" "$@"
}

if [ "$1" = "--remove" ]; then
	vm sudo rm -f "$EXPORTS_FILE" && vm sudo exportfs -ra
	echo "no longer exported (the folder $FOLDER is still in $VM)"
	exit 0
fi

echo "== the server in $VM"
state=$(multipass info "$VM" 2>&1 | awk '/^State/ {print $2}')
echo "        $VM is: ${state:-unknown}"
if [ "$state" != "Running" ]; then
	echo "        \$ multipass start $VM"
	multipass start "$VM" || { echo "could not start $VM"; exit 1; }
fi
echo "        \$ multipass exec $VM -- timeout $LIMIT sh -c 'command -v exportfs || ls /usr/sbin/exportfs'"
if ! multipass exec "$VM" -- timeout "$LIMIT" sh -c 'command -v exportfs || ls /usr/sbin/exportfs'; then
	echo "        installing nfs-kernel-server (up to ${INSTALL_LIMIT}s)"
	LIMIT=$INSTALL_LIMIT vm sudo DEBIAN_FRONTEND=noninteractive apt-get install -y nfs-kernel-server || { echo "could not install nfs-kernel-server"; exit 1; }
fi
vm sudo mkdir -p "$FOLDER" || exit 1
vm sudo chown "$CLIENT_UID:$CLIENT_UID" "$FOLDER" || exit 1
vm sudo mkdir -p /etc/exports.d
vm sudo sh -c "echo '$FOLDER *(rw,sync,no_subtree_check,insecure,fsid=0)' > $EXPORTS_FILE"
vm sudo systemctl enable --now nfs-server
vm sudo exportfs -ra || { echo "exportfs failed"; exit 1; }
vm sudo exportfs -v

if [ -z "$SERVER_IP" ]; then
	SERVER_IP=$(multipass exec "$VM" -- hostname -I | awk '{print $1}')
fi
[ -n "$SERVER_IP" ] || { echo "no address for $VM; set SERVER_IP"; exit 1; }
echo "== the client test against $SERVER_IP:2049 as uid $CLIENT_UID"

cd "$(dirname "$0")/.." || exit 1
$MVN test -Dtest=NfsClientLiveTest -Dsurefire.failIfNoSpecifiedTests=false \
	-Dnfs.live.host="$SERVER_IP" -Dnfs.live.mount=/ -Dnfs.live.uid="$CLIENT_UID" -Dnfs.live.gid="$CLIENT_UID"
status=$?
if [ $status -eq 0 ]; then echo "PASS"; else echo "FAIL (maven exit $status)"; fi
exit $status
