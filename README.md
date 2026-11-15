# parley-nfs

An NFS version 4.0 server ([RFC 7530](https://www.rfc-editor.org/rfc/rfc7530), XDR in
[RFC 7531](https://www.rfc-editor.org/rfc/rfc7531)) built on parley-net's non-blocking `NioServer`.
It shares one folder of any FileSource: a local folder, a memory file system, or any other
parley-files provider.

## Status: phase 1

Supported:

1. ONC RPC over TCP (RFC 5531 record marking), the NULL and COMPOUND procedures.
2. AUTH_NONE and AUTH_SYS credentials.
3. Every NFSv4.0 operation except those listed under "Not yet", with client IDs and leases
   (SETCLIENTID, SETCLIENTID_CONFIRM, RENEW), opens and share reservations (OPEN, OPEN_CONFIRM,
   OPEN_DOWNGRADE, CLOSE) and owner sequence numbers with replies saved for retransmissions.
4. READ, WRITE (unstable or stable) and COMMIT through a random access stream kept open per file.

Not yet (answered with NFS4ERR_NOTSUPP):

1. Byte-range locks: LOCK, LOCKT, LOCKU, RELEASE_LOCKOWNER (phase 2).
2. Delegations, ACLs and named attributes (phase 3).
3. RPCSEC_GSS / Kerberos V5 (its own phase).
4. Hard links, symbolic links (followed, not shown as links), devices, sockets and fifos.

There is no stable storage of client state, so a restarted server has no grace period: clients
start over with SETCLIENTID and can't reclaim opens.

## Running

```
java -cp ... us.bringardner.parley.nfs.server.NfsServer -Dparley.nfs.export=/srv/share
```

`main` runs the server with parley-net's `ServerMain` (a service or a standalone process). Its
settings file is `parley-nfs.properties` in Parley's configuration folder, or the file
`parley.nfs.configFile` names.

| Setting | Default | |
|---|---|---|
| `parley.nfs.port` | 2049 | |
| `parley.nfs.export` | none | The folder to share: a path on the default FileSource factory or a `filesource:` URL |
| `parley.nfs.exportName` | export | Its name in logs and permission checks |
| `parley.nfs.readOnly` | false | |
| `parley.nfs.domain` | this host's DNS domain, else localdomain | The NFSv4 domain of owner names (user@domain); it must match the clients' |
| `parley.nfs.leaseTime` | 90s | |
| `parley.nfs.ioSize` | 1048576 | The most bytes one READ returns or one WRITE takes |
| `parley.nfs.workerThreads` | 16 | Threads that run requests (one at a time per connection) |
| `parley.nfs.idmap` | none | Properties file of `uid.<number>=<user name>` |
| `parley.nfs.anonymous` | none | The user unmapped uids and AUTH_NONE stand for; none refuses them |
| `parley.nfs.authCacheTime` | 60s | How long a permission answer is kept |
| `parley.nfs.fileIdleTime` | 30s | How long an unused file stays open |
| `parley.nfs.replayCacheSize` | 1024 | How many replies of COMPOUNDs that changed something are kept for a client that sends the call again; 0 for none |

## Who may do what

Without a user registry every client that can connect may read (and, unless `readOnly`, change)
the export, as a classic NFS export to a trusted network.

With a user registry (parley-net's provider setting, or `setUserRegistry`) each call's AUTH_SYS uid
is mapped to a user name with the id map, the user is found in the registry, and the registry is
asked for the `read` or `write` permission on the export (a resource of type `nfs-export`). AUTH_SYS
proves nothing; the server vouches for it, and a registry that handles `VouchedCredential` decides
whether to trust that.

## Testing

`mvn test` runs the protocol tests against a memory file system with a small test client. The
server has also been tried with libnfs (`nfs-ls`, `nfs-cat`, `nfs-cp`, `nfs-stat` with `?version=4`).

It has also been run against pynfs's NFSv4.0 server tests (`nfs4.0/testserver.py ... all`). What still
fails there is phase 2 and 3 work (LOCK, LOCKT, LOCKU, RELEASE_LOCKOWNER), objects a FileSource can't
make (symbolic links, devices, sockets, fifos), and checks the server leaves to the user registry:
UNIX mode bits (OPEN20, WRT19) and changing a file's owner (SATT18).
