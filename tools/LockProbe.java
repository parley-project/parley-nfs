import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.lock.FileSourceLock;

/**
 * Another program that locks a file through FileSource, for trying parley.nfs.mirrorLocks with real NFS
 * clients (decision D68). Run it as its own JVM on the machine that runs the server, with the server's
 * class path:
 * <pre>
 * java -cp "$CP" tools/LockProbe.java try  /Volumes/Data/nfs-test/f [position [size [shared]]]
 * java -cp "$CP" tools/LockProbe.java hold /Volumes/Data/nfs-test/f [position [size [shared]]]
 * </pre>
 * try: asks for the lock, says whether it got it, lets go, and exits 0 if it got it, 1 if not.
 * hold: takes the lock, waits for Enter, lets go; exits 1 at once if it can't take it.
 * position defaults to 0, size to 0 (to the end of the file), shared to false (exclusive).
 * It must use the same lock settings as the server (parley.files.lockDirectory, for one).
 */
public class LockProbe {

	public static void main(String[] args) throws Exception {
		if( args.length < 2 || !(args[0].equals("try") || args[0].equals("hold")) ) {
			System.err.println("usage: LockProbe try|hold <file> [position [size [shared]]]");
			System.exit(2);
		}
		FileSource file = FileSourceFactory.getDefaultFactory().resolve(args[1]);
		long position = args.length > 2 ? Long.parseLong(args[2]) : 0;
		long size = args.length > 3 ? Long.parseLong(args[3]) : 0;
		boolean shared = args.length > 4 && Boolean.parseBoolean(args[4]);
		String what = (shared ? "shared " : "exclusive ")+position+"+"+(size == 0 ? "end" : Long.toString(size))+" of "+args[1];
		FileSourceLock lock;
		try {
			lock = file.tryLock(position, size, shared);
		} catch (OverlappingFileLockException e) {
			lock = null;
		} catch (IOException e) {
			System.err.println("could not ask: "+e);
			System.exit(2);
			return;
		}
		if( lock == null ) {
			System.out.println("NOT GOT "+what+": another program holds a lock that overlaps it");
			System.exit(1);
		}
		try (FileSourceLock l = lock) {
			System.out.println("GOT "+what);
			if( args[0].equals("hold") ) {
				System.out.println("holding it; press Enter to let go");
				System.in.read();
			}
		}
		System.out.println("released");
		System.exit(0);
	}
}
