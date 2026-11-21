# parley-nfs

An NFS version 4.0 server ([RFC 7530](https://www.rfc-editor.org/rfc/rfc7530), XDR in
[RFC 7531](https://www.rfc-editor.org/rfc/rfc7531)) built on parley-net's non-blocking `NioServer`.
It shares one folder of any FileSource: a local folder, a memory file system, or any other
parley-files provider.

## Status

Supported:

1. ONC RPC over TCP (RFC 5531 record marking), the NULL and COMPOUND procedures.
2. AUTH_NONE and AUTH_SYS credentials, and RPCSEC_GSS with Kerberos V5 (see "Kerberos").
   More than one export is shared through a read-only folder of exports (see "More than one export").
3. Every NFSv4.0 operation except those listed under "Not yet", with client IDs and leases
   (SETCLIENTID, SETCLIENTID_CONFIRM, RENEW), opens and share reservations (OPEN, OPEN_CONFIRM,
   OPEN_DOWNGRADE, CLOSE) and owner sequence numbers with replies saved for retransmissions.
4. READ, WRITE (unstable or stable) and COMMIT through a random access stream kept open per file.
5. Byte-range locks (LOCK, LOCKT, LOCKU, RELEASE_LOCKOWNER): shared and exclusive, per lock-owner,
   advisory, kept by the server for its NFS clients only, or also as FileSource locks with
   `parley.nfs.mirrorLocks` (see "Locks"). A lock that can't be had is denied at once (the client tries
   again); a client's locks go when its lease runs out; there is no reclaim after a restart. On Linux both
   `flock` and `fcntl` locks work on the mount.
6. Read and write delegations, which survive a restart (see "Delegations").
7. Access control lists (see "Access control lists"), a state folder that keeps file identities and lists
   across a restart (see "File handles"), and a folder for each user.

Not yet (answered with NFS4ERR_NOTSUPP):

1. Named attributes (OPENATTR).
2. Hard links, devices, sockets and fifos. A FileSource can't make them, so a client gets errors it can name:
   `ln` says "Invalid cross-device link" (NFS4ERR_XDEV) and `mkfifo` and `mknod` say "Operation not permitted"
   (NFS4ERR_PERM). Symbolic links are served (see below).
   NFS4ERR_NOTSUPP and NFS4ERR_BADTYPE have no Linux error text ("Unknown error 524").

Symbolic links keep the text they were made with: `ln -s ../x/y link` stores `../x/y`, and `readlink` gives it back,
for a link to nothing too. The server never follows a link itself (the client does, with the text, in its own
view): a link has the type, a size of the length of its text and the mode 0777, a LOOKUP or OPEN through one is
NFS4ERR_SYMLINK, and REMOVE takes the link and not what it points to. It needs a FileSource whose factory keeps the
text (`createSymbolicLink(link, text)` and `getLinkText()`: the local and memory ones do); another one answers
NFS4ERR_PERM as before. The mode, owner and times of a link can't be set.

`df` shows the space of the export's FileSource (total, free and usable by the server); a FileSource
that can't tell shows 0. File counts (`df -i`) aren't reported.

There is no stable storage of open and lock state, so a restarted server has no grace period for them:
clients start over with SETCLIENTID and can't reclaim opens or locks. File handles are kept (see "File
handles"), and so are delegations when `parley.nfs.stateDir` is set (see "Delegations").

## Running

```
java -cp ... us.bringardner.parley.nfs.server.NfsServer -Dparley.nfs.export=/srv/share
```

`tools/run-server.sh` starts it in the foreground from a checkout (`--sudo` runs it as root, other settings as `-Dparley.nfs.name=value`; see the
script's header). `main` runs the server with parley-net's `ServerMain` (a service or a standalone process). Its
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
| `parley.nfs.stateDir` | none | A folder (a path or `filesource:` URL) that keeps one node file per export, so handles survive a restart (see "File handles"). Without it the start-up log says what is lost: stale handles of renamed files after a restart, no access control lists, no delegations across a restart |
| `parley.nfs.delegations` | false | true to give read delegations (see "Delegations"); none are given while `mirrorLocks` is on |
| `parley.nfs.writeDelegations` | false | true (with `delegations`) to give write delegations too |
| `parley.nfs.mirrorLocks` | false | true to hold the clients' byte-range locks as FileSource locks too (see "Locks") |
| `parley.nfs.minorVersion` | 2 | The server's highest NFSv4 minor version: 2 serves NFSv4.2 (SEEK, ALLOCATE, DEALLOCATE, COPY, READ_PLUS), 4.1 (sessions) and 4.0; 1 serves 4.1 and 4.0; 0 serves 4.0 only |
| `parley.nfs.client.minorVersion` | 2 | The client's highest NFSv4 minor version (a system property): it tries that version and goes down (4.2, 4.1, 4.0) to what the server has; 0 uses 4.0 only. In 4.1 a client takes delegations over the session's back channel, so the server needs no route to the client |
| `parley.nfs.enforceModes` | false | true to check the mode bits of files that have no access control list, by the caller's registry name (see "Permissions") |
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
is mapped to a user name (the user whose `uid` attribute it is, else the id map), the user is found in the registry, and the registry is
asked for the `read` or `write` permission on the export (a resource of type `nfs-export`). AUTH_SYS
proves nothing; the server vouches for it, and a registry that handles `VouchedCredential` decides
whether to trust that.

With a registry, set `parley.nfs.anonymous` to a user who can read the export, even if every client
you expect has a mapped uid: a Linux mount first calls the server without a credential (AUTH_NONE),
and without an anonymous user that is refused ("access denied by server"). Root is not mapped
unless the id map says so, so it acts as the anonymous user too. The id map is read when the server
starts; the registry's own user file is read again when it changes. A refused call is logged once
for each answer (a minute, `parley.nfs.authCacheTime`) at INFO with the user and the reason.

## Owners as numbers

An owner or group is sent as the number the registry or the id map has for that name (D15, D48). When it has none and
the file is on the local file system, the operating system's own uid or gid is sent (D75), so a client sees the real
numbers whether or not an id map lists them. Other file systems, and Windows, still send name@domain.

## Permissions (the mode bits)

By default the mode bits (owner, group, other) of a file are only reported, and the registry's `read` and
`write` on the export are the only check. Set `parley.nfs.enforceModes=true` and the server checks them too,
for every file that has no access control list of its own: read, write, list, look up, create, remove and
rename. The caller is known by the registry name (the same as for access control lists): the mode is turned
into a list (owner, group, everyone) and the owner is the file's owner by name, the group the caller's registry
groups. A file that has a list of its own is decided by that list alone. The registry still checks first. The mapped root
(AUTH_SYS uid 0 mapped to a registry user, see below) is not held to mode bits, as root on a Unix file system is not; a
file's own list and the registry's read and write still apply to it.

A new file is given to the caller who made it when the provider can take it (a local file whose
operating system has a user of that name and a server allowed to change owners, or a memory file system); when it can't, the file keeps the
owner it got, which is said once in the log, and the modes are then checked against that owner. Only root may change the owner or
group of a file, and only when the id map (or a registry user's `uid`) maps uid 0 to a registry user with write
on the export; an unmapped root is the anonymous user and is refused. The server passes the name to the file system's own FileSource (a number is taken through the id map first; one nobody maps goes to the
provider as it is, which a local file takes as the operating system's uid or gid), and a provider that has no such user or can't change owners refuses (NFS4ERR_PERM).

`tools/chown-test.sh` checks this with a Linux client (a Multipass VM): an ordinary user is refused, the mapped root changes
the owner and group, and names or numbers nobody has are refused; `--unmapped` checks that root is refused without `uid.0`.

## Numbers for users and groups

A user in the registry can carry a `uid` attribute and a group a `gid` attribute (in a FileUserRegistry users
file: `tony, ..., uid=1000` on the user line). An AUTH_SYS call is the user whose `uid` it is; a file's owner is
sent as its user's `uid`, and its group as its group's `gid`, so a Linux client shows them as its own users.
`parley.nfs.idmap` (`uid.1000=tony`) is the fallback for a user without the attribute. If two users have one
`uid`, the registry can't tell who a call is and the id map decides.

## A folder for each user

A user whose registry principal has the attribute `nfs.root` (a path inside the export, like an FTP root:
`nfs.root=/home/tony`) sees that folder as the root of the export: PUTROOTFH gives it, LOOKUPP stops there, and a
file handle outside it is NFS4ERR_STALE. A user without the attribute sees the whole export. With several
exports the same path is looked for inside each, and an export without it can't be entered (NFS4ERR_NOENT). A
value with `..` leaves the user nothing (NFS4ERR_ACCESS). Symbolic links the FileSource has on disk are the
client's to follow, not the server's, so they don't lead the server out of the folder; the registry's read and write permission still applies to the whole export.

## Access control lists

With `parley.nfs.stateDir` set, files and folders can carry an NFSv4 ACL (RFC 7530 section 6): the `acl`
attribute (ALLOW and DENY entries, ordered, with the inheritance flags) and `aclsupport`. Lists are kept in
`<export>.acls` in the state folder and follow a file through a rename. The registry's read/write check still
applies first; a file with a list must also allow the operation. A file with no list behaves as before, and
GETATTR shows a list made from its mode. Without a state folder the server says `aclsupport` is 0.

## Delegations

With `parley.nfs.delegations=true` an NFSv4.1 or 4.2 client can also ask for a directory delegation
(`GET_DIR_DELEGATION`): the server then tells it, over the session's back channel (`CB_NOTIFY`), when another client adds,
removes or renames an entry in that folder, so a viewer needn't poll. `NfsClient.watchDirectory(path, listener)` is the
client side. Changes made to the shared files outside the server are not seen, and the delegation isn't kept across
a restart.

`parley.nfs.delegations=true` lets the server give a client a read delegation when it opens a file for reading, so
the client can serve reads from its cache until another client wants to write. It needs a callback address that
works: the client's SETCLIENTID names it, the server calls it (CB_NULL) when the client confirms, and a client that
doesn't answer gets none. Only the host the client connects from is called. When another client opens the file for
writing, changes its size, removes or renames it, the server recalls the delegation and answers NFS4ERR_DELAY until
the client returns it (or the lease runs out and it is revoked). Programs on the server's machine that write the
same files are not seen. Off by default.

`parley.nfs.writeDelegations=true` (with `parley.nfs.delegations=true`) also gives a write delegation to a client
that opens a file for writing when no other client has it open or holds a delegation of it. The client may keep its
changes until the server asks for them. Another client's open, or another host's request for the file's size, change
or times, READ, WRITE, SETATTR, REMOVE or RENAME, recalls it; the other client waits (NFS4ERR_DELAY) until the holder
has written its changes and returned the delegation. A request that names no client (GETATTR, or READ and WRITE with
a special stateid) from the holder's own host is taken to be the holder's. If the holder doesn't return the delegation
within the lease it is revoked, and the changes it hadn't written are lost: the log says so. READDIR's attributes
and LOCKT are not checked against a write delegation.

With `parley.nfs.mirrorLocks=true` no delegation is given, read or write (D69, D70): a client holding one takes its
byte-range locks itself and never tells the server, so they could not be held as file locks (tried with a read
delegation: another program was given a lock the client held). The server says so when it starts.

With `parley.nfs.stateDir` set, the server keeps the delegations it gives in `delegations.state` there (written
before the client is told), so they survive a restart (decision D67). A restarted server has a grace period of one
lease time: a client that comes back with SETCLIENTID and the same client name, as the same principal, takes its
delegation back with OPEN (CLAIM_PREVIOUS), and a Linux client does so for each file it still has open. Until then
a call that would conflict with the delegation (another client's open, a write open, REMOVE, RENAME, SETATTR, and,
for a write delegation, READ, WRITE and a request for the size or times) gets NFS4ERR_GRACE; calls on other files go
on. A delegation nobody comes back for within the lease is given up, and the log says so for a write delegation
(its changes are lost); so is one whose client has restarted since. Opens and locks aren't kept, so a CLAIM_PREVIOUS
for one that held no delegation gets NFS4ERR_NO_GRACE and the client opens again. Without `stateDir` nothing is
kept, as before. `tools/reclaim-test.sh` times a real client's reclaim after a restart with one command (a Multipass
VM opens a file, you restart the server, the script reports how soon the file answers).

## Locks

By default the byte-range locks are the server's own: advisory, and seen by its NFS clients only. With
`parley.nfs.mirrorLocks=true` they are also taken as FileSource locks (`tryLock`) on the file, so another Parley
program that locks the same file (a mail server and its mailbox, say) sees them, and a lock it holds is seen by
the clients: a LOCK over it is denied (NFS4ERR_DENIED, with the range asked for, client ID 0 and no owner), and
LOCKT reports it. Notes:

- The file system decides how far a FileSource lock reaches (`FileSource.getLockScope()`): this JVM for most, the
  host for a local file. A lock of a program in the same JVM is refused whether it is shared or not.
- The server holds, per file, the pieces that its clients' locks cover together, each shared or exclusive. When a
  client's lock changes, a piece that has to shrink is let go and its rest taken again, so for a moment another
  program could take it; if it does, the server logs a warning and the client keeps thinking it has the lock.
- A file that can't be locked (it is gone, its FileSource has no locks) is logged once; the clients' locks stand
  without a mirror. A client's locks are released with its lease, and all of them when the server stops.
- `tools/LockProbe.java` is another program that tries or holds a FileSource lock on a file, for checking the mirror
  against real clients; it must run on the server's machine with the same lock settings as the server.
- `tools/mirror-lock-test.sh` runs the whole check with one command on the server's machine: the probe runs there and the
  clients' side runs in a Multipass VM (`--lease` adds the lease expiry check, about 2.5 minutes). Its check F shows
  why no delegations are given while locks are mirrored: it is skipped when the server gives none.
- A local file's lock may be a lock file beside it (`.parley-locks`, see parley-files), which would show in an
  exported folder; set `parley.files.lockDirectory` to put them elsewhere.

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

## A client

`us.bringardner.parley.nfs.client.NfsClient` is a generic NFSv4.0 client in this module (D77): AUTH_SYS, one connection,
one mounted path. `info`, `exists`, `list`, `mkdir`, `remove`, `read`, `readAll` and `write` take paths below the mount.
It sends no callback address, so the server gives it no delegations, and it renews its lease while open. Not yet
built: Kerberos, locks, recovery after a server restart, rename, links and attribute changes. A FileSource over it
is a separate module.

## Testing

`mvn test` runs the protocol tests against a memory file system with a small test client. The
server has also been tried with a Linux kernel mount (`tools/nfs-mount-test.sh`, run in the client; `tools/mirror-lock-test.sh` checks the mirror of locks and `tools/reclaim-test.sh` the reclaim of a delegation after a restart, with a Multipass VM) and libnfs (`nfs-ls`, `nfs-cat`, `nfs-cp`, `nfs-stat` with `?version=4`).

It has also been run against pynfs's NFSv4.0 server tests (`nfs4.0/testserver.py ... all`). What still
fails there is objects the server doesn't serve (hard links, devices, sockets, fifos), and checks the server leaves to the user registry:
UNIX mode bits (OPEN20, WRT19) and changing a file's owner (SATT18).
