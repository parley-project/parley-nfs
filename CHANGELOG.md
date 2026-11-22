# Changelog

## parley-nfs 2.0.0 (unreleased)

### Added (2026-11-22: CLONE in the client, D104)

- **`NfsClient.clone(from, fromOffset, to, toOffset, count)` and `cloneBlockSize(path)`** (NFSv4.2, RFC 7862): ask the
  server to make a range of one file share its storage with a range of another, copy-on-write, so nothing is copied.
  It works where the server's file system keeps clones (btrfs, XFS with reflinks, ...); the offsets and the count must
  be multiples of the `clone_blksize` attribute, which `cloneBlockSize` reads (0 when the server doesn't say). Elsewhere
  the server answers NFS4ERR_NOTSUPP (or NFS4ERR_XDEV across file systems, NFS4ERR_INVAL for a misaligned range), which
  comes back as an `NfsException`. The server side is not built: this server answers CLONE with NFS4ERR_NOTSUPP, as
  `FileSource` has no operation for it.

### Fixed (2026-11-22)

- **CLONE, COPY_NOTIFY, IO_ADVISE, LAYOUTERROR, LAYOUTSTATS, OFFLOAD_CANCEL, OFFLOAD_STATUS and WRITE_SAME answered
  NFS4ERR_BADXDR** instead of NFS4ERR_NOTSUPP: they fell through into GET_DIR_DELEGATION in the server's operation switch.

### Added (2026-11-21: extended attributes, RFC 8276, D101)

- **GETXATTR, SETXATTR, LISTXATTRS, REMOVEXATTR (NFSv4.2) and the `xattr_support` attribute** (RFC 8276). The server
  keeps nothing itself: it asks the file for its `getUserDefinedFileAttributeView()` (parley-files; local files and the
  memory provider have one), and says `xattr_support` true only for a
  file that does. Names are opaque text up to 255 bytes, values up to 64 KiB (`NFS4ERR_XATTR2BIG` beyond);
  reading and listing need read permission and setting and removing need write permission, through the same checks
  as file data; the change attribute moves when an attribute is created, changes or goes (not when the same value is
  set again); ACCESS answers the three new bits. A file system that keeps none answers NFS4ERR_NOTSUPP, and below
  NFSv4.2 the operations are illegal. Named attributes (OPENATTR, RFC 7530) are still not offered.
- **`NfsClient.isXattrSupported`, `getXattr`, `setXattr` (either, create or replace), `listXattrs` and
  `removeXattr`.** Needs parley-files with the extended-attribute methods.

### Added (2026-11-21: READ_PLUS, D100)

- **READ_PLUS (NFSv4.2, RFC 7862 section 15.10)**: the server answers it with data and hole segments. A file here
  has no holes of its own, so a run of 4096 or more zero bytes in what is read is sent as a hole (the client reads it
  as zeros all the same, and the zeros are not sent); the rest is data. `NfsClient.readPlus(path, offset, count)` reads
  with it and returns an `NfsReadPlus` (the bytes, the holes as zeros, end of file, how many bytes were holes);
  NFS4ERR_NOTSUPP below NFSv4.2. SEEK still reports only the hole at the end of the file.

### Added (2026-11-21: the change attribute of a folder, D97)

- **`NfsClient.changeId(path)`**: the change attribute (RFC 7530 section 5.8.1.4) of a file or folder, one
  small GETATTR. A folder's moves when an entry is added, removed or renamed, so a watcher can tell that a folder
  is as it was without listing it.

### Changed (2026-11-21: threads from Parley, D96)

- Every thread the server and client start is made with parley-core's `NamedExecutors` (named daemon
  threads), none with `new Thread`: the server's workers, timer, notifier, callbacks and stop thread, and the
  client's lease renewer, reader, callback listener, delegation returner and directory-watch deliverer.
  Names are kept; the server's worker threads are now `name-worker`, `name-worker-2` ... (were `-1`, `-2`).
  Needs parley-core with `NamedExecutors`.

### Added (2026-11-20: what a FileSource needs of the client, T10, D87)

- Directory delegations and `CB_NOTIFY`, step (d) of T5 (D93): a client on NFSv4.1 or 4.2 that takes delegations can ask for a folder (`GET_DIR_DELEGATION`) and be told, over the session's back channel, when another client adds, removes or renames an entry in it. `NfsClient.watchDirectory(path, listener)` returns an `NfsDirectoryWatch`, or null when the server gives none (poll then); the `NfsDirectoryListener` gets `NfsDirectoryEvent`s (ADDED, REMOVED, RENAMED, with the name and the new name) in order, in a thread of its own, and `lost(reason)` when the watch ends (the server took the delegation back, the connection was made again, the watch or the client was closed). The server (with `delegations=true`) tells every other holder from CREATE, REMOVE, RENAME and OPEN-with-create, in the order the changes were made; the client that makes a change isn't told of it; a client that can't be told loses the delegation; removing a folder recalls its delegations. Changes made outside the server (to the files it shares) are not seen. Not kept across a server restart. Found by the live test: Linux nfsd on mx1 gives directory delegations but recalls them on a change instead of sending CB_NOTIFY, so `watchDirectory` gives such a delegation back and returns null (poll).
- NFSv4.2 extras, step (e) of T5 (D92): minor version 2 is served and tried first (the client goes 4.2, 4.1, 4.0; `parley.nfs.minorVersion` and `parley.nfs.client.minorVersion` default to 2). The server does `SEEK` (a file has data to its end and one hole, at the end), `ALLOCATE` (a range past the end makes the file longer), `DEALLOCATE` (the range reads as zeros) and `COPY` inside the server (done before the reply; a copy from another server is NFS4ERR_NOTSUPP); `CLONE`, `READ_PLUS`, `WRITE_SAME`, `IO_ADVISE` and the rest are NFS4ERR_NOTSUPP, and a 4.2 operation in a 4.1 COMPOUND is NFS4ERR_OP_ILLEGAL. `NfsClient` has `seek(path, offset, hole)`, `allocate`, `deallocate`, `copy(from, fromOffset, to, toOffset, count)` and `supportsV42()`; they are NFS4ERR_NOTSUPP on a connection of 4.1 or 4.0.
- `NfsClientV42LiveTest`: SEEK, ALLOCATE, DEALLOCATE and COPY against a real server; skipped unless `nfs.live.host` is set and the server answers 4.2. Passed against Linux nfsd on mx1 (AUTH_SYS).
- NFSv4.1 on the client, step (a) of T5 (D91): `NfsClient` tries 4.1 first (`EXCHANGE_ID`, `CREATE_SESSION`, `SEQUENCE` before every call, `RECLAIM_COMPLETE`, `FREE_STATEID` for a lock's stateid, `DESTROY_SESSION` and `DESTROY_CLIENTID` on close) and goes on with 4.0 when the server answers MINOR_VERS_MISMATCH. `getMinorVersion()` says which. The system property `parley.nfs.client.minorVersion=0` holds it to 4.0; the server's `parley.nfs.minorVersion=0` serves 4.0 only.
- NFSv4.1 restart, step (c) of T5 (D91): `RECLAIM_COMPLETE` is enforced. A 4.1 client that says it is done gives up the delegations of the run before that it didn't take back, so others don't wait for it for the rest of the grace period, and the grace period ends when no client is left to come back. A reclaim (`CLAIM_PREVIOUS`) after `RECLAIM_COMPLETE` gets NFS4ERR_NO_GRACE. The client sends `RECLAIM_COMPLETE` after it connects and after it has reclaimed its locks on a reconnect, which is what Linux nfsd needs to remember a 4.1 client across a restart. `NfsClientRestartLiveTest` runs over 4.1 by default.
- NFSv4.1 back channel, step (b) of T5 (D91): a session can carry the server's callbacks over the connection that made it (`CREATE_SESSION4_FLAG_CONN_BACK_CHAN`, `BIND_CONN_TO_SESSION`, `BACKCHANNEL_CTL`). The server probes the path with `CB_SEQUENCE` on the session's first call, gives delegations once it works, and sends `CB_RECALL` over it, so a client behind NAT or a firewall takes delegations too and `CB_PATH_DOWN` doesn't come up. The client reads its connection in a thread of its own (when it takes delegations) and answers `CB_SEQUENCE` and `CB_RECALL`. A client that takes delegations now uses 4.1 like the others.
- NFSv4.1 on the server, step (a) of T5 (D91): a COMPOUND with minor version 1 runs in a session. `EXCHANGE_ID`, `CREATE_SESSION`, `SEQUENCE`, `DESTROY_SESSION`, `DESTROY_CLIENTID`, `RECLAIM_COMPLETE`, `TEST_STATEID`, `FREE_STATEID` and `SECINFO_NO_NAME` are served; the slot table keeps the reply of a request, so one sent again gets it and does not run twice. Seqids and `OPEN_CONFIRM` are not used in 4.1, and a stateid with seqid 0 is the current one. No callback channel yet: no delegations are given to a 4.1 client (step (b)). `Nfs4` has the 4.1 operation codes and statuses.
- `NfsException` says what went wrong in words: "open demo/a.txt: no such file or directory (NFS4ERR_NOENT)" instead of "NFS4 status 2". `Nfs4Errors.text/name/describe(status)` give the words for all 66 statuses; `getStatus()` still has the number. A viewer shows this message to a person.
- Fixed: a server that can't reach the client's callback address answers RENEW with NFS4ERR_CB_PATH_DOWN, which still renews the lease. The client took it for a lost lease, made a new client id every renewal and so lost its locks. It now keeps its state and gives its delegations back. Found by the live restart test against Linux nfsd.
- Fixed: the reply to a lock's reclaim after a server restart can carry a delegation (Linux nfsd sends a write delegation, to be returned at once when it can't reach the callback address). The client didn't read it, so it was never given back: another client could not open the file (DELAY) and RENEW answered CB_PATH_DOWN for ever. It is now read and returned at once. Found by the live restart test, with the server's own list of its state.
- `NfsClientRestartLiveTest`: against a real server, a lock is reclaimed after the server restarts (D85) and another client still can't take the range. Skipped unless `nfs.live.host` is set and `nfs.live.restart.command` (a shell command that restarts the server) or `nfs.live.restart.manual=true` is.
- The test classes are published in a `-tests` jar, and `TestKdc` (a Kerberos KDC for tests) is public, so modules built on the client can test Kerberos without a copy (parley-files-nfs, D88).
- `NfsFileInfo` also has the owner, the group (as the server names them: "name@domain" or a number), the access, change
  and creation times (-1 when the server has none) and the number of links.
- `setAttributes(path, NfsSetAttributes)` changes the permission bits, owner, group, access time and modify time (SETATTR);
  `access(path, mask)` asks what the user may do (ACCESS); `readLink(path)` and `symlink(path, text)`.
- `openFile(path, write, create)` returns an `NfsOpenFile` that stays open: `read(offset, count)`, `write(offset, ...)`,
  `size()`, `truncate(size)` and `close()`, without an open and close for each call. After a lost connection or a restart
  the file is opened again by its path (the state of the open is gone).
- `createNew(path)` makes a file that isn't there yet and says whether it did: the server decides when callers race for
  the name (OPEN with GUARDED4), so exactly one gets `true`.
- Tests: `NfsClientFilesTest`.

### Added (2026-11-20: the client takes read delegations, T9b)

- `new NfsClient(host, port, mount, uid, gid, timeoutMillis, true)` (or the Kerberos form with the flag) runs a callback
  server (`ClientCallbacks`: CB_NULL and CB_COMPOUND with CB_RECALL) and gives the server its address, so that a server
  that gives delegations can. The server calls from its own address only. Without the flag the client offers no callback,
  as before, and gets no delegation.
- A read delegation lets `readAll` keep the file's bytes (files up to 4 MiB): the next `readAll` asks the server only for
  the file's handle, to be sure the name still leads to the same file, and reads nothing. The bytes are dropped when
  the server recalls the delegation (CB_RECALL, answered at once; DELEGRETURN follows from another thread, or from
  the operation that is waiting on the server), when this client changes anything (write, truncate, remove, rename),
  and when the connection is made again. `getDelegationCount()` says how many are held. They are returned when the
  client is closed. A write delegation is returned with the close of the open that got it: the client doesn't write behind one.
- The file operations share one open-owner (an open-owner's first open is confirmed and gets no delegation, so one for each
  operation never got any). After a failed open or close the owner is replaced, as its seqid is not known.
- Fixed: an OPEN the server answered DELAY or GRACE was sent again with the same seqid, which the server replays: the
  answer was DELAY every time until the retries ran out. It is now made again with the next seqid (RFC 7530 section 9.1.7).
- Tests: `NfsClientDelegationTest` (no flag, no server setting, keeping, own write, another client's write recalls and is
  then done, close returns, restart). A real Linux server can only call back a client it can reach, so the live
  test has no delegation case.

### Added (2026-11-20: the client takes byte range locks, T9)

- `NfsClient.lock(path, offset, length, write)` takes a byte range lock (LOCK; `NfsLock.TO_END` for the rest of the
  file) and returns an `NfsLock` that is held until `unlock()`/`close()` (LOCKU, RELEASE_LOCKOWNER and a close of the
  open it kept). A lock another client holds is `NfsLockDeniedException` (with the holder's range and type; the server
  doesn't wait). `testLock` asks without taking (LOCKT). Each lock has an open and a lock-owner of its own.
- After a lost connection or a restart the client reclaims each lock in the server's grace period (OPEN with
  CLAIM_PREVIOUS, then LOCK with reclaim). A lock the server refuses is lost: `isHeld()` turns false, `getLostReason()`
  says why and the handler of `setLockLostHandler` is called. This package's own server keeps no locks across a restart and
  has no grace period for them (D21), so against it every lock is lost on a restart; a Linux server gives them back.
- Tests: `NfsClientLockTest`; `NfsClientLiveTest.byteRangeLocks` (the reclaim itself needs a real server that restarts).

### Added (2026-11-20: the client logs in with Kerberos, T8)

- `NfsClient` can connect with Kerberos (RPCSEC_GSS, RFC 2203) instead of AUTH_SYS: `new NfsClient(host, port, mount,
  timeoutMillis, kerberos)`. `NfsKerberos` says who the user is (a key table with `NfsKerberos.withKeytab`, a
  `Subject`, or null for the ticket cache of kinit) and the protection: `AUTHENTICATION` (krb5), `INTEGRITY` (krb5i) or
  `PRIVACY` (krb5p). The server's name is "nfs@host" or "nfs/host@REALM". One security context is made for each
  connection; after a lost connection or a server restart (the server no longer has the context) the client's
  recovery (D81) makes a new one. Closing the client ends the context. The caller closes the `NfsKerberos`.
- `NfsClientLiveTest` takes `nfs.live.krb5.service`, `.level`, `.principal` and `.keytab` to run against a real
  Kerberos server. The tests (`NfsClientGssTest`) use the Kerby KDC of `GssNfsTest`, now one `TestKdc` for the JVM.

### Changed (2026-11-20: a start with no state folder warns)

- A server started with no `parley.nfs.stateDir` logs a warning (it was an info line) that says what it costs: the
  handle of a file that was renamed, or whose path is longer than 119 bytes, is stale after a restart; access control
  lists can't be set; delegations aren't kept (when they are on). It says so that this is fine for a test or a short
  job and that a server meant to keep running needs the folder. Nothing else changes; the setting is still opt-in (D41).

### Fixed (2026-11-20: an idle node dropped that could not come back, T11)

- Without a state folder, the node table dropped an idle node whose id was not its path's hash (a renamed file), when
  there were more nodes than parley.nfs.maxNodes. Decision D42 says such a node stays, since its id is lost on a restart
  with no node file; the check looked at the table in memory, which is filled with or without a file. The node is kept
  now. This was also the cause of the test `NodeTableTest.withoutAFileOnlyNodesThatComeBackAreDropped` failing
  about one run in four (the nodes were dropped in the order of their times, and the time of a, b and c is the same
  millisecond or not). A new test, `aRenamedNodeWithNoFileIsKeptWhateverItsAge`, does not depend on that.

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
