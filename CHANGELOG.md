# Changelog

## parley-nfs 2.0.0 (unreleased)

### Fixed (2026-11-20: a provider that can't change owners, T2)

- A file system whose `setOwner` or `setGroup` throws `UnsupportedOperationException` (instead of answering false)
  made SETATTR answer NFS4ERR_SERVERFAULT and log an error; it is NFS4ERR_PERM now, as for one that answers false
  (decision D74). New tests: `ChownProviderTest` (a server that is not the superuser, one that is, a provider that
  refuses with an exception, one that fails with an IOException) and, for T1, `AclNfsTest.aStoredListDecidesForTheMappedRootToo`
  (the mapped root is not held to the mode bits but a stored list decides for it like for anyone).

### Added (2026-11-20: the client recovers, T7)

- `NfsClient` gets its connection and client id back after the server restarts, the connection is lost or the lease
  runs out. The client holds nothing between operations (each one opens and closes), so there is nothing to reclaim:
  recovery is a new connection, SETCLIENTID and the mount. The renewal thread does it as soon as a renewal fails, and
  an operation does it when it meets a lost connection or an answer that the client id or state is gone. After that
  the operation is run once more if it is safe to repeat (reads, listings, write at an offset, truncate), and after a
  server's rejection (the operation was not done) any operation is. A lost connection during a make-a-folder, remove or
  rename throws the connection error (it is not known if it was done), with the connection made again for the next call.
  A lost server is logged once as a warning, and a reconnect as an info line (System.Logger).

### Added (2026-11-20: client rename and truncate)

- `NfsClient.rename(from, to)` (a file at the new name is replaced; the folder for the new name must exist) and
  `NfsClient.truncate(path, size)` (shorter drops the end, longer adds zeros; the file must exist). Still not in the
  client: Kerberos, delegations and callbacks, locks, links, other attribute changes.

### Added (2026-11-20: the client against a real server)

- `NfsClientLiveTest` runs the client against any NFS server (skipped unless `-Dnfs.live.host` is set), and
  `tools/client-live-test.sh` sets up the Linux kernel's server in a Multipass VM and runs it.

### Added (2026-11-19: a client, D77)

- `us.bringardner.parley.nfs.client.NfsClient`: a generic NFSv4.0 client (AUTH_SYS) that mounts a path of a server and
  gets information about a file, lists a folder, reads, writes, makes folders and removes, and renews its lease. It uses
  the `rpc` package of this module and knows nothing of FileSource (a FileSource over it is a separate module).
  Not yet: Kerberos, delegations and callbacks, locks, links, attribute changes.

### Added (2026-11-19: the mapped root and modes, D76)

- With `parley.nfs.enforceModes`, the mapped root (AUTH_SYS uid 0 mapped to a registry user with write on the export)
  is no longer checked against mode bits; a file's own access control list and the registry still apply.

### Added (2026-11-19: operating system ids, D75)

- A local file's owner and group are sent as the operating system's uid and gid when the registry and the id map
  don't name them (they were sent as name@domain, which a Linux client shows as nobody).
- The mapped root's chown to a number the id map doesn't have goes to the provider as it is (a local file takes it as
  the uid or gid), instead of NFS4ERR_BADOWNER.

### Added (2026-11-19: chown by the mapped root)

- SETATTR of the owner or group is allowed for root (AUTH_SYS uid 0) when uid 0 is mapped to a registry user (the id
  map's uid.0=name or a user's uid attribute) who may write the export; anyone else is refused as before, and so is an
  unmapped root (decision D74). The name goes to the FileSource (a FileSourceUser or FileSourceGroup, as owner-on-create does too), so each provider
  decides: a number nobody has is NFS4ERR_BADOWNER, a provider with no such user or that can't change owners
  answers NFS4ERR_PERM. A list or the modes don't stop that root.

### Added (2026-11-19: parley.nfs.enforceModes)

- `parley.nfs.enforceModes` (default false): true to check the mode bits of a file that has no access control list,
  by the caller's registry name, for read, write, list, look up, create, remove and rename; ACCESS then answers for
  the caller. A new file is given to the caller who made it when the file system can; otherwise it is said once in
  the log (decision D73). Nothing changes while the setting is off.

### Added (2026-11-19: symbolic links)

- Symbolic links are created (CREATE of type NF4LNK) and read (READLINK) with the exact text they were made with,
  as the client sends it; a link has its own type, the size of its text and mode 0777; LOOKUP or OPEN through one is
  NFS4ERR_SYMLINK; REMOVE takes the link, not its target (decision D72). Needs parley-files with
  `createSymbolicLink(FileSource, String)` and `getLinkText()`. SETATTR of a link sets nothing.

### Changed (2026-11-18: ACCESS no longer depends on the server's user)

- ACCESS for a file answers MODIFY, EXTEND and DELETE when any class (owner, group or other) has a write bit, the same
  for every caller. Before, it asked whether the user the server runs as could write the file, which says nothing about
  the caller (decision D71).

### Added (2026-11-18: tools for trying the server with real clients)

- `tools/LockProbe.java` (another program that tries or holds a FileSource lock), `tools/lock-client.py` (fcntl locks
  from a Linux client), `tools/mirror-lock-test.sh` (the mirror of locks, one command, with a Multipass VM) and
  `tools/reclaim-test.sh` (the reclaim of a delegation after a restart).

### Changed (2026-11-18: delegations and mirrored locks)

- **No delegations, read or write, while `parley.nfs.mirrorLocks` is on (D69, D70).** A client with a delegation takes
  its byte-range locks itself, so they never reached the server to be held as file locks. The server logs it at
  start-up.

### Added (2026-11-18: locks as file locks)

- **`parley.nfs.mirrorLocks` holds the clients' byte-range locks as FileSource locks too (D68).** Off by default.
  Another Parley program that locks the file sees them; a lock another program holds makes a LOCK come back
  NFS4ERR_DENIED and shows in LOCKT. A lock that can't be given leaves what the owner held as it was.

### Changed (2026-11-18: messages about the state folder)

- **Without `parley.nfs.stateDir` the log says what that costs.** The start-up line names the handles, the access
  control lists and (with delegations on) the delegations that don't survive a restart; the first time a client
  tries to set an access control list, a line says why it got NFS4ERR_ATTRNOTSUPP. A `stateDir` that names a file,
  or a folder that can't be made, is refused at start with a message that says which.

### Added (2026-11-18: delegations across a restart)

- **Delegations survive a restart of the server (D67).** With `parley.nfs.stateDir` set they are kept in
  `delegations.state`; a restarted server has a grace period of one lease time, in which the client takes a delegation
  back with OPEN (CLAIM_PREVIOUS) and conflicting calls on that file get NFS4ERR_GRACE. A delegation nobody comes back
  for is given up (a warning for a write delegation), and so is one whose client started again.

### Changed (2026-11-18: quieter logs)

- **A RPCSEC_GSS call for a service that isn't in `parley.nfs.sec` is logged at the debug level, not INFO.** A
  Linux client probes krb5i and krb5p before settling on an allowed service, so the INFO lines were noise.

### Added (2026-11-17: write delegations)

- **`parley.nfs.writeDelegations` (default false, needs `parley.nfs.delegations=true`): write delegations (D62 to
  D65).** A client that opens a file for writing, with a working callback address, while no other client has the
  file open or holds a delegation of it, can be given a write delegation: it may keep its changes until the
  server asks for them. Another client's open (read too), another host's request for the size, change or times,
  READ, WRITE, SETATTR, REMOVE or RENAME of the file sends a CB_RECALL and gets NFS4ERR_DELAY until DELEGRETURN. A
  delegation not returned within the lease is revoked, and a warning says the client's unwritten changes are lost.
  The space limit is a size of 0. Callbacks now carry an AUTH_SYS credential, which a Linux client needs for
  CB_RECALL. Revocations of read delegations are logged too.

### Added (2026-11-17: a folder for each user)

- **The `nfs.root` principal attribute confines a user to a folder of the export (D49).** It is the root the user
  sees; LOOKUPP stops there and handles outside it are stale.

### Added (2026-11-17: numbers from the registry)

- **uid and gid come from the registry's principals (D48).** An AUTH_SYS uid is the user whose `uid` attribute it is
  (`UserRegistry.findByAttribute`); a file's owner is sent as its user's `uid` and its group as its group's `gid`.
  The id map stays the fallback. Needs parley-iam's `principal-ids` branch.

### Added (2026-11-17: read delegations)

- **`parley.nfs.delegations` (default false): read delegations (RFC 7530 section 10.4).** A client that opens a
  file for reading, with a callback address that answers a CB_NULL, can be given a read delegation. Another
  client's open for write, WRITE, size change, REMOVE or RENAME of the file sends it a CB_RECALL and gets
  NFS4ERR_DELAY until DELEGRETURN; a delegation not returned within the lease is revoked. The callbacks go
  over parley-net's NioClient and only to the host the client's SETCLIENTID came from. Write delegations,
  CLAIM_DELEGATE_PREV and DELEGPURGE are not built. A delegation protects against other NFS clients only.

### Added (2026-11-17: access control lists, RFC 7530 section 6)

- **The `acl` and `aclsupport` attributes (ALLOW and DENY entries)**, kept in the state folder
  (`<export>.acls`, needs `parley.nfs.stateDir`; without it `aclsupport` is 0 and SETATTR of `acl` is
  NFS4ERR_ATTRNOTSUPP). OWNER@, GROUP@, EVERYONE@, user and group names, and numbers (AUTH_SYS uid/gid or
  the registry's `uid`/`gid` attributes) are understood. File, directory, no-propagate and inherit-only
  flags are honored when a file or folder is created. AUDIT and ALARM entries are refused.
- **Enforced on** LOOKUP, READDIR, OPEN, READ, WRITE, CREATE, REMOVE, RENAME, SETATTR and ACCESS. The
  registry's read/write check stays the outer gate; a file with a list must also allow. A file with no list
  behaves as before. A list and the mode are independent; the list wins when present. A file's owner always
  keeps READ_ACL and WRITE_ACL.
- Needs `UserRegistry.findByAttribute` (parley-iam branch principal-ids).

### Added (2026-11-16: file handles that survive a restart)

- **`parley.nfs.stateDir`: a node file per export** keeps the ids that can't be worked out from a
  path again (renamed files, clashes, paths too long for a handle), so a handle still finds its
  file after a restart, even when the file was renamed and another took its old name.
- **`parley.nfs.maxNodes` (100000): the node table no longer grows for ever.** Nodes not asked for
  in two minutes (and with no open or lock) are dropped from memory and come back with the same id.
  Without a state folder only nodes whose id is their path's hash are dropped.

### Added (2026-11-16: Kerberos)

- **RPCSEC_GSS with Kerberos V5 (RFC 2203), all three levels: `krb5`, `krb5i`, `krb5p`.** Set
  `parley.nfs.sec`, `parley.nfs.krb5.principal` and `parley.nfs.krb5.keytab`; `sys` stays the default.
  Built on Java's JGSS: INIT and CONTINUE_INIT, DATA with a sequence window of 128, DESTROY, context
  expiry with the ticket, checksummed verifiers, integrity and privacy bodies. A principal of the
  server's own realm is the registry user without the realm. SECINFO lists the flavors taken,
  strongest first. A sent-again COMPOUND is answered from the replay cache with a new sequence number.
  Tested with an Apache Kerby KDC (a test dependency) and a JGSS client.

### Added (2026-11-16: more than one export)

- **Several exports in a read-only folder of exports (RFC 7530 section 7).** `parley.nfs.exports`
  names them, each has `parley.nfs.export.<name>` and optionally `.readOnly`; `parley.nfs.export`
  alone still shares one folder as the root. Each export is authorized separately, has its own
  fsid, and a RENAME between two is NFS4ERR_XDEV. The folder lists only the exports the client may
  read, unless `parley.nfs.hideUnreadableExports` is false. File handles of a server with several
  exports carry an export tag (version 2); the path that fits in a handle is 115 bytes there.
  `NfsServer.setExports`, `getExports`, `findExport`; a `Node` knows its `Export`.
- The test of the settings list now checks the names the server really asks for (it didn't before:
  it used a prefix that doesn't start with `parley.`).

### Fixed (2026-11-16: found mounting with a user registry)

- **The start-up warnings about `parley.nfs.*` settings are gone.** The module now lists its setting
  names (`META-INF/parley/settings.list`), so parley-net's start-up check knows them.
- **The "shares export" line was logged twice**, because `startAndWait` read the settings and then
  the `start()` it calls read them again.
- **A refused call is logged at INFO** (once for each cached answer), with the user and the reason.
- **`tools/nfs-mount-test.sh` checks other users:** with `READ_UID` (a uid the registry lets read but not write) and `DENIED_UID` (one it refuses) set, it runs checks as those uids with `sudo -n -u '#uid'`.
- **README:** with a registry, `parley.nfs.anonymous` is needed for a Linux mount to work.

### Changed (2026-11-16)

- **`df` shows space.** space_avail, space_free and space_total come from the export's FileSource
  (0 when it can't tell). File counts are still not reported.
- **Hard links, symbolic links, devices, sockets and fifos give errors a Linux client can name.**
  LINK answers NFS4ERR_XDEV ("Invalid cross-device link") and CREATE of the others NFS4ERR_PERM
  ("Operation not permitted"), where NFS4ERR_NOTSUPP showed as "Unknown error 524".

### Added

- **An NFS version 4.0 server (RFC 7530), phase 1.** `NfsServer` on parley-net's `NioServer` shares one
  folder of any FileSource over TCP: ONC RPC record marking and XDR, NULL and COMPOUND, AUTH_NONE and
  AUTH_SYS, client IDs and leases, opens with share reservations and owner sequence numbers, READ, WRITE
  and COMMIT. Delegations, ACLs, named attributes and RPCSEC_GSS answer NFS4ERR_NOTSUPP for now.
- **Phase 2: byte-range locks.** LOCK, LOCKT, LOCKU and RELEASE_LOCKOWNER, as RFC 7530 describes:
  shared and exclusive locks per lock-owner over 64-bit ranges (upgrade, downgrade and split), lock
  stateids for READ and WRITE, CLOSE and RELEASE_LOCKOWNER refused while locks are held. Locks are
  advisory, kept by the server for its NFS clients only, denied at once rather than waited for, and
  dropped with the client's lease.
- `tools/nfs-mount-test.sh` checks a mount from a Linux client: files, folders, a large copy, renames,
  attributes, locks, git, tar and SQLite.
- With a user registry, AUTH_SYS uids are mapped to users (`parley.nfs.idmap`) and every call is checked
  for the `read` or `write` permission on the export.
- A duplicate request cache (`parley.nfs.replayCacheSize`, 1024): a COMPOUND that changed something
  (CREATE, REMOVE, RENAME, LINK, SETATTR) and is sent again with the same xid gets its first reply.
- A file whose owner is a user in the id map (`parley.nfs.idmap`) is sent as owned by that uid
  (the lowest when the user has several), so a Linux client shows the owner it mapped instead of
  `nobody`; other owners are still sent as `name@domain`.

### Fixed (found with a Linux client)

- An OPEN for writing opens the file's write stream at once and keeps it while the file has an open
  for writing, so it can write after the file gets a mode without write (git makes its objects with
  mode 0444 and then writes them; the WRITEs failed with NFS4ERR_NOENT).

### Fixed (found by pynfs, the Linux NFS server tests)

- OPEN creating a file with a size attribute makes the file that size; OPEN UNCHECKED4 of an existing
  file ignores a size other than 0 instead of answering NFS4ERR_INVAL.
- An owner's own open counts in share reservation checks (NFS4ERR_SHARE_DENIED).
- OPEN_DOWNGRADE accepts only access and deny an OPEN of the file asked for (NFS4ERR_INVAL).
- LOCK for a new lock-owner counts as the open-owner's seqid whatever its status, so the owner's next
  OPEN or CLOSE isn't NFS4ERR_BAD_SEQID.
- Stateids of a client that rebooted are NFS4ERR_EXPIRED.
- GETATTR and READDIR answer NFS4ERR_INVAL for write-only attributes, and ignore attributes past the
  ones the server knows instead of answering NFS4ERR_BADXDR.
- ACCESS reports only the bits that mean something for the file's type.
- READDIR with a maxcount too small for an empty list is NFS4ERR_TOOSMALL; COMMIT past 2^64 is
  NFS4ERR_INVAL.
