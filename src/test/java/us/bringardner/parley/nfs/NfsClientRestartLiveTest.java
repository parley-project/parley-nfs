/**
 * <PRE>
 *
 * Copyright Tony Bringardner 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 */
package us.bringardner.parley.nfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsException;
import us.bringardner.parley.nfs.client.NfsLock;

/**
 * A lock is given back to the client after the NFS server restarts (D85): the client makes a new client id and
 * reclaims its open and its lock in the server's grace period, and another client still can't take the range.
 * <p>
 * Skipped unless nfs.live.host is set and one way of restarting the server is given:
 * <ul>
 * <li>nfs.live.restart.command, a shell command that restarts it and ends when it has, for example
 * <code>ssh -i key.pem ubuntu@host sudo systemctl restart nfs-server</code>, or
 * <li>nfs.live.restart.manual=true: the test says when, and waits nfs.live.restart.wait seconds (120) while you restart it.
 * </ul>
 * The other properties are those of NfsClientLiveTest (nfs.live.port, nfs.live.mount, nfs.live.uid, nfs.live.gid; AUTH_SYS only).
 * nfs.live.restart.grace (150) is how many seconds to wait for the server's grace period to end. The work happens in a
 * folder of its own, which is removed at the end.
 */
public class NfsClientRestartLiveTest {

	private String host;
	private int port;
	private String mount;
	private long uid;
	private long gid;
	private NfsClient client;
	private NfsClient other;
	private String work;

	private NfsClient newClient() throws IOException {
		return new NfsClient(host, port, mount, uid, gid, 20000);
	}

	@BeforeEach
	public void connect() throws Exception {
		host = System.getProperty("nfs.live.host");
		Assumptions.assumeTrue(host != null && !host.isEmpty(), "no nfs.live.host: not testing against a real server");
		String command = System.getProperty("nfs.live.restart.command");
		Assumptions.assumeTrue((command != null && !command.isEmpty()) || Boolean.getBoolean("nfs.live.restart.manual"),
				"no nfs.live.restart.command or nfs.live.restart.manual: no way to restart the server");
		port = Integer.getInteger("nfs.live.port", 2049);
		mount = System.getProperty("nfs.live.mount", "/");
		uid = Long.getLong("nfs.live.uid", 1000L);
		gid = Long.getLong("nfs.live.gid", 1000L);
		client = newClient();
		work = "restart-live-" + System.currentTimeMillis();
		client.mkdir(work);
	}

	@AfterEach
	public void disconnect() throws Exception {
		try {
			if( other != null ) {
				other.close();
			}
		} finally {
			if( client != null ) {
				try {
					if( work != null && client.exists(work) ) {
						client.remove(work + "/locked.txt");
						client.remove(work);
					}
				} finally {
					client.close();
				}
			}
		}
	}

	private void restartServer() throws Exception {
		String command = System.getProperty("nfs.live.restart.command");
		if( command != null && !command.isEmpty() ) {
			System.out.println("Restarting the server: "+command);
			Process p = new ProcessBuilder("sh", "-c", command).inheritIO().start();
			try {
				if( !p.waitFor(120, TimeUnit.SECONDS) ) {
					throw new IOException("The restart command did not end in 120 seconds");
				}
				assertEquals(0, p.exitValue(), "the restart command failed");
			} finally {
				p.destroyForcibly();
			}
		} else {
			int wait = Integer.getInteger("nfs.live.restart.wait", 120);
			System.out.println(">>> RESTART THE NFS SERVER NOW (for example: sudo systemctl restart nfs-server). Waiting "+wait+" seconds.");
			Thread.sleep(wait * 1000L);
		}
	}

	@Test
	public void aLockIsReclaimedAfterTheServerRestarts() throws Exception {
		String file = work + "/locked.txt";
		client.write(file, 0, "0123456789".getBytes(StandardCharsets.UTF_8));
		java.util.List<NfsLock> lost = new java.util.concurrent.CopyOnWriteArrayList<NfsLock>();
		client.setLockLostHandler(lost::add);
		NfsLock mine = client.lock(file, 0, 5, true);
		assertTrue(mine.isHeld());

		restartServer();

		// the next call finds the connection gone, makes a new client id and reclaims the lock
		long deadline = System.currentTimeMillis() + Long.getLong("nfs.live.restart.grace", 150L) * 1000L;
		while( true ) {
			try {
				assertTrue(client.info(work).isDirectory());
				break;
			} catch (IOException e) {
				// the server may still be coming up
				if( System.currentTimeMillis() > deadline ) {
					throw e;
				}
				Thread.sleep(2000);
			}
		}
		assertTrue(lost.isEmpty(), "the lock was lost: "+(lost.isEmpty() ? "" : lost.get(0).getLostReason()));
		assertTrue(mine.isHeld(), "not held: "+mine.getLostReason());

		// Another client asks the server about the range (LOCKT) every few seconds, for a whole lease period and more, and
		// the answers are written down: GRACE while the server is in its grace period, then "held" for as long as it has
		// our lock. "Free" is a failure: either the server never took the lock back, or it dropped it again (a lease that ran out).
		other = newClient();
		long hold = Long.getLong("nfs.live.restart.hold", 150L);
		StringBuilder timeline = new StringBuilder("lease "+client.getLeaseSeconds()+"s; seconds after the restart: ");
		long start = System.currentTimeMillis();
		boolean sawHeld = false;
		String failure = null;
		while( failure == null && System.currentTimeMillis() - start < hold * 1000L ) {
			long t = (System.currentTimeMillis() - start) / 1000L;
			String answer;
			try {
				answer = other.testLock(file, 0, 5, true) != null ? "held" : "FREE";
			} catch (NfsException e) {
				answer = e.getStatus() == 10013 ? "grace" : "status "+e.getStatus();
			}
			timeline.append(t).append("s ").append(answer).append(", ");
			System.out.println("restart live test: "+t+"s after the restart, the range is "+answer);
			if( "held".equals(answer) ) {
				sawHeld = true;
			} else if( "FREE".equals(answer) ) {
				failure = "the server does not hold the lock any more"+(sawHeld ? " (it did earlier: it was dropped)" : " (it never did: not reclaimed)");
			}
			if( failure == null ) {
				Thread.sleep(3000);
			}
		}
		assertTrue(failure == null, failure+". "+timeline);
		assertTrue(sawHeld, "the range was never reported held. "+timeline);
		assertTrue(lost.isEmpty(), "the lock was lost: "+(lost.isEmpty() ? "" : lost.get(0).getLostReason()));
		assertTrue(mine.isHeld(), "not held: "+mine.getLostReason());
		mine.unlock();
		other.lock(file, 0, 5, true).unlock();
	}
}
