# parley-nfs

An NFS version 4.0 server ([RFC 7530](https://www.rfc-editor.org/rfc/rfc7530), XDR in
[RFC 7531](https://www.rfc-editor.org/rfc/rfc7531)) built on parley-net's non-blocking `NioServer`.
It shares one folder of any FileSource: a local folder, a memory file system, or any other
parley-files provider.

## Status: phases 1, 2, 4 and 5

Supported:

1. ONC RPC over TCP (RFC 5531 record marking), the NULL and COMPOUND procedures.
2. AUTH_NONE and AUTH_SYS credentials, and RPCSEC_GSS with Kerberos V5 (see "Kerberos").
   More than one export is shared through a read-only folder of exports (see "More than one export").
3. Every NFSv4.0 operation except those listed under "Not yet", with client IDs and leases
   (SETCLIENTID, SETCLIENTID_CONFIRM, RENEW), opens and share reservations (OPEN, OPEN_CONFIRM,
   OPEN_DOWNGRADE, CLOSE) and owner sequence numbers with replies saved for retransmissions.
4. READ, WRITE (unstable or stable) and COMMIT through a random access stream kept open per file.
5. Byte-range locks (LOCK, LOCKT, LOCKU, RELEASE_LOCKOWNER): shared and exclusive, per lock-owner,
   advisory, kept by the server for its NFS clients only. A lock that can't be had is denied at once
   (the client tries again); a client's locks go when its lease runs out; there is no reclaim after
   a restart. On Linux both `flock` and `fcntl` locks work on the mount.

Not yet (answered with NFS4ERR_NOTSUPP):

1. Delegations, ACLs and named attributes (phase 3).
2. Hard links, symbolic links (followed, not shown as links), devices, sockets and fifos. A FileSource
   can't make them, so a client gets errors it can name: `ln` says "Invalid cross-device link"
   (NFS4ERR_XDEV) and `ln -s`, `mkfifo` and `mknod` say "Operation not permitted" (NFS4ERR_PERM).
   NFS4ERR_NOTSUPP and NFS4ERR_BADTYPE have no Linux error text ("Unknown error 524").

`df` shows the space of the export's FileSource (total, free and usable by the server); a FileSource
that can't tell shows 0. File counts (`df -i`) aren't reported.

There is no stable storage of client state, so a restarted server has no grace period: clients
start over with SETCLIENTID and can't reclaim opens. File handles are kept (see "File handles").

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
| `parley.nfs.exports` | none | Instead of `export`: the names of several exports, comma separated (see below) |
| `parley.nfs.export.<name>` | none | The folder to share as that export (a path or `filesource:` URL) |
| `parley.nfs.export.<name>.readOnly` | false | |
| `parley.nfs.hideUnreadableExports` | true | List in the folder of exports only the exports the client may read; false lists them all |
| `parley.nfs.domain` | this host's DNS domain, else localdomain | The NFSv4 domain of owner names (user@domain); it must match the clients' |
| `parley.nfs.leaseTime` | 90s | |
| `parley.nfs.ioSize` | 1048576 | The most bytes one READ returns or one WRITE takes |
| `parley.nfs.workerThreads` | 16 | Threads that run requests (one at a time per connection) |
| `parley.nfs.idmap` | none | Properties file of `uid.<number>=<user name>`; a file whose owner is a user in it shows as owned by that uid on clients |
| `parley.nfs.anonymous` | none | The user unmapped uids and AUTH_NONE stand for; none refuses them |
| `parley.nfs.authCacheTime` | 60s | How long a permission answer is kept |
| `parley.nfs.fileIdleTime` | 30s | How long an unused file stays open |
| `parley.nfs.stateDir` | none | A folder (a path or `filesource:` URL) that keeps one node file per export, so handles survive a restart (see "File handles") |
| `parley.nfs.maxNodes` | 100000 | The most files per export kept in memory; those nobody has asked for lately go first |
| `parley.nfs.replayCacheSize` | 1024 | How many replies of COMPOUNDs that changed something are kept for a client that sends the call again; 0 for none |

## More than one export

`parley.nfs.export` shares one folder, which is what a client sees at `server:/`. To share several,
list their names and give each a folder:

```
parley.nfs.exports=docs,media
parley.nfs.export.docs=/srv/docs
parley.nfs.export.media=/srv/media
parley.nfs.export.media.readOnly=true
```

A client then sees a read-only folder at `server:/` that holds one folder for each export, so
`mount server:/` gives them all and `mount server:/docs` just one. Each export is a separate
file system: it has its own fsid and its own permission (`nfs-export:<name>`), and a RENAME from
one to another is refused with "Invalid cross-device link". The folder of exports itself needs no
permission; with a registry it lists only the exports the client may read (a lookup of one it
may not read is "Permission denied"), unless `parley.nfs.hideUnreadableExports` is false.
The names are single folder names (no `/`), so the layout is flat. Set either `export` or
`exports`, not both.

## Who may do what

Without a user registry every client that can connect may read (and, unless `readOnly`, change)
the export, as a classic NFS export to a trusted network.

With a user registry (parley-net's provider setting, or `setUserRegistry`) each call's AUTH_SYS uid
is mapped to a user name with the id map, the user is found in the registry, and the registry is
asked for the `read` or `write` permission on the export (a resource of type `nfs-export`). AUTH_SYS
proves nothing; the server vouches for it, and a registry that handles `VouchedCredential` decides
whether to trust that.

With a registry, set `parley.nfs.anonymous` to a user who can read the export, even if every client
you expect has a mapped uid: a Linux mount first calls the server without a credential (AUTH_NONE),
and without an anonymous user that is refused ("access denied by server"). Root is not mapped
unless the id map says so, so it acts as the anonymous user too. The id map is read when the server
starts; the registry's own user file is read again when it changes. A refused call is logged once
for each answer (a minute, `parley.nfs.authCacheTime`) at INFO with the user and the reason.

## Access control lists

With `parley.nfs.stateDir` set, files and folders can carry an NFSv4 ACL (RFC 7530 section 6): the `acl`
attribute (ALLOW and DENY entries, ordered, with the inheritance flags) and `aclsupport`. Lists are kept in
`<export>.acls` in the state folder and follow a file through a rename. The registry's read/write check still
applies first; a file with a list must also allow the operation. A file with no list behaves as before, and
GETATTR shows a list made from its mode. Without a state folder the server says `aclsupport` is 0.

## File handles

A file handle holds the file's id and, when it fits in 128 bytes, its path. The id is a hash of the
path the file had when the server first saw it, and a rename keeps it while the server runs. That
leaves three weak spots after a restart: a renamed file's old handle names a path that is gone (or
now holds another file), a file whose path is too long for a handle has no path to find it by, and
the server's table of files grows with every file a client has listed.

Set `parley.nfs.stateDir` to a folder (on any FileSource, not inside an export) and the server keeps
a small node file there for each export: just the ids that can't be worked out from a path again
(renamed files, files whose id was taken by another, and long paths). It is a journal that is
appended to and written out about every five seconds, so a crash can lose the last few seconds
of renames; it is rewritten tidily at each start, and ids of files that no longer exist are
forgotten in the background after a start. A handle whose file is gone answers NFS4ERR_STALE.

`parley.nfs.maxNodes` limits the files held in memory: when there are more, those not asked for in
the last two minutes (and with no open or lock) are dropped, and made again with the same id when
asked for. Without a state folder only files whose id is their path's hash can be dropped, since
that is the only id that comes back.

Not covered: a file renamed by another program on the server's machine. It looks like a new file
at its new path, and its old handles go stale. Telling the two apart needs a file identity on
FileSource (parley-files).

## Kerberos (RPCSEC_GSS)

`parley.nfs.sec` lists the security flavors the server takes: `sys` (the default), `krb5`
(authentication), `krb5i` (and a checksum of every call and reply) and `krb5p` (and encryption),
for example `krb5p` alone, or `sys,krb5,krb5i,krb5p`. Any `krb5` flavor needs the server's own
principal and its keys:

| Setting | Meaning |
|---|---|
| `parley.nfs.sec` | default `sys`; a comma separated list of `sys`, `krb5`, `krb5i`, `krb5p` |
| `parley.nfs.krb5.principal` | the server's principal, `nfs/<host name>@<REALM>` |
| `parley.nfs.krb5.keytab` | the file with that principal's keys |

The realm and the KDC come from the JVM's Kerberos configuration (`/etc/krb5.conf`, or
`-Djava.security.krb5.conf=`). It is Java's own JGSS underneath; nothing else is needed.

A client's Kerberos principal `alice@EXAMPLE.COM` is the user `alice` when the realm is the
server's own (a principal of another realm keeps its whole name); that user is then looked up in
the registry and its `read` and `write` permissions are checked on each export, as for AUTH_SYS,
but with nothing to map: the id map isn't used, and the anonymous user never stands in for a
Kerberos principal. SECINFO lists the flavors taken, strongest first. A context lasts as long as
its ticket, or until the client destroys it.

## Testing

`mvn test` runs the protocol tests against a memory file system with a small test client. The
server has also been tried with a Linux kernel mount (`tools/nfs-mount-test.sh`, run in the client) and libnfs (`nfs-ls`, `nfs-cat`, `nfs-cp`, `nfs-stat` with `?version=4`).

It has also been run against pynfs's NFSv4.0 server tests (`nfs4.0/testserver.py ... all`). What still
fails there is objects the server doesn't serve (hard links, symbolic links, devices, sockets, fifos), and checks the server leaves to the user registry:
UNIX mode bits (OPEN20, WRT19) and changing a file's owner (SATT18).
