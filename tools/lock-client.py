#!/usr/bin/env python3
"""
Byte-range locks (fcntl) from a Linux NFS client, for trying parley.nfs.mirrorLocks (D68) together with
tools/LockProbe.java on the server's machine.

  lock-client.py lock FILE START LENGTH [r|w] [SECONDS]   take the lock (non blocking), hold it SECONDS
                                                          (default: until Enter), say whether it was got
  lock-client.py test FILE START LENGTH [r|w]             ask who holds a conflicting lock (F_GETLK, LOCKT)

LENGTH 0 is to the end of the file. The type defaults to w (exclusive).
"""
import fcntl, os, struct, sys, time, errno

FLOCK = "hhqqi"          # l_type, l_whence, l_start, l_len, l_pid (64 bit Linux)

def usage():
    sys.exit(__doc__)

def main(argv):
    if len(argv) < 5 or argv[1] not in ("lock", "test"):
        usage()
    mode, path, start, length = argv[1], argv[2], int(argv[3]), int(argv[4])
    write = not (len(argv) > 5 and argv[5] == "r")
    ltype = fcntl.F_WRLCK if write else fcntl.F_RDLCK
    what = "%s %d+%s of %s" % ("exclusive" if write else "shared", start, length or "end", path)
    fd = os.open(path, os.O_RDWR if write else os.O_RDONLY)
    packed = struct.pack(FLOCK, ltype, os.SEEK_SET, start, length, 0)
    if mode == "test":
        out = struct.unpack(FLOCK, fcntl.fcntl(fd, fcntl.F_GETLK, packed))
        if out[0] == fcntl.F_UNLCK:
            print("FREE: %s could be locked" % what)
            return 0
        print("CONFLICT with %s lock at %d+%d (pid %d) for %s"
              % ("exclusive" if out[0] == fcntl.F_WRLCK else "shared", out[2], out[3], out[4], what))
        return 1
    try:
        fcntl.fcntl(fd, fcntl.F_SETLK, packed)
    except OSError as e:
        if e.errno in (errno.EACCES, errno.EAGAIN):
            print("NOT GOT %s: another lock conflicts" % what)
            return 1
        raise
    print("GOT %s" % what)
    if len(argv) > 6:
        time.sleep(float(argv[6]))
    else:
        print("holding it; press Enter to let go")
        sys.stdin.readline()
    os.close(fd)
    print("released")
    return 0

sys.exit(main(sys.argv))
