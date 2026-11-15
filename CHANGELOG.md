# Changelog

## parley-nfs 2.0.0 (unreleased)

### Added

- **An NFS version 4.0 server (RFC 7530), phase 1.** `NfsServer` on parley-net's `NioServer` shares one
  folder of any FileSource over TCP: ONC RPC record marking and XDR, NULL and COMPOUND, AUTH_NONE and
  AUTH_SYS, client IDs and leases, opens with share reservations and owner sequence numbers, READ, WRITE
  and COMMIT. Locks, delegations, ACLs, named attributes and RPCSEC_GSS answer NFS4ERR_NOTSUPP for now.
- With a user registry, AUTH_SYS uids are mapped to users (`parley.nfs.idmap`) and every call is checked
  for the `read` or `write` permission on the export.
- A duplicate request cache (`parley.nfs.replayCacheSize`, 1024): a COMPOUND that changed something
  (CREATE, REMOVE, RENAME, LINK, SETATTR) and is sent again with the same xid gets its first reply.

### Fixed (found by pynfs, the Linux NFS server tests)

- OPEN creating a file with a size attribute makes the file that size; OPEN UNCHECKED4 of an existing
  file ignores a size other than 0 instead of answering NFS4ERR_INVAL.
- An owner's own open counts in share reservation checks (NFS4ERR_SHARE_DENIED).
- OPEN_DOWNGRADE accepts only access and deny an OPEN of the file asked for (NFS4ERR_INVAL).
- LOCK for a new lock-owner (NFS4ERR_NOTSUPP until phase 2) counts as the open-owner's seqid, so the
  owner's next OPEN or CLOSE isn't NFS4ERR_BAD_SEQID.
- Stateids of a client that rebooted are NFS4ERR_EXPIRED.
- GETATTR and READDIR answer NFS4ERR_INVAL for write-only attributes, and ignore attributes past the
  ones the server knows instead of answering NFS4ERR_BADXDR.
- ACCESS reports only the bits that mean something for the file's type.
- READDIR with a maxcount too small for an empty list is NFS4ERR_TOOSMALL; COMMIT past 2^64 is
  NFS4ERR_INVAL.
