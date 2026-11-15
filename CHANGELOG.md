# Changelog

## parley-nfs 2.0.0 (unreleased)

### Added

- **An NFS version 4.0 server (RFC 7530), phase 1.** `NfsServer` on parley-net's `NioServer` shares one
  folder of any FileSource over TCP: ONC RPC record marking and XDR, NULL and COMPOUND, AUTH_NONE and
  AUTH_SYS, client IDs and leases, opens with share reservations and owner sequence numbers, READ, WRITE
  and COMMIT. Locks, delegations, ACLs, named attributes and RPCSEC_GSS answer NFS4ERR_NOTSUPP for now.
- With a user registry, AUTH_SYS uids are mapped to users (`parley.nfs.idmap`) and every call is checked
  for the `read` or `write` permission on the export.
