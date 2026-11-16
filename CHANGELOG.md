# Changelog

## parley-nfs 2.0.0 (unreleased)

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
