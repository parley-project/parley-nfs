package us.bringardner.parley.nfs;

import java.lang.System.Logger.Level;

import us.bringardner.parley.logging.testing.QuietErr;
import us.bringardner.parley.logging.testing.TestLogLevels;

/**
 * For a test that expects errors (a server that refuses to start): nothing is logged by this module's classes
 * and nothing reaches System.err while it is open, so the expected errors do not show in the test output.
 * Close it (try-with-resources) when the expected part is done.
 */
final class ExpectedErrors implements AutoCloseable {

	private final TestLogLevels.Scope level = TestLogLevels.set("us.bringardner.parley.nfs", Level.OFF);
	private final QuietErr err = new QuietErr();

	@Override
	public void close() {
		err.close();
		level.close();
	}
}
