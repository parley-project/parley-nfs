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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.TestClient.Ops;
import us.bringardner.parley.nfs.TestClient.Result;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.XdrEncoder;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/**
 * NFSv4.1 sessions on the server (RFC 8881 sections 2.10 and 18.35 to 18.37), over raw XDR: the setup, the
 * exactly-once slots, and what a session doesn't allow.
 */
public class NfsV41SessionTest {

	private MemoryFileSourceFactory mem;
	private FileSource root;
	private NfsServer server;
	private final List<TestClient> clients = new ArrayList<TestClient>();

	@BeforeEach
	public void start() throws IOException {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer("test.nfs.v41", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
	}

	@AfterEach
	public void stop() throws Exception {
		for (TestClient c : clients) {
			c.close();
		}
		server.stop();
		assertTrue(server.awaitStopped(10000));
		mem.disConnect();
	}

	private TestClient client() throws IOException {
		TestClient c = new TestClient(server.getLocalPort());
		clients.add(c);
		return c;
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	/** The result of EXCHANGE_ID. */
	private static final class Exchanged {
		long clientid;
		int sequence;
		int flags;
	}

	private static Exchanged exchange(TestClient c, String name, byte verifier) throws IOException {
		Ops ops = new Ops().op(Nfs4.OP_EXCHANGE_ID);
		ops.args().writeFixedOpaque(new byte[] {verifier, 2, 3, 4, 5, 6, 7, 8});
		ops.args().writeOpaque(bytes(name));
		ops.args().writeInt(0).writeInt(0).writeInt(0);
		Result r = c.compound(ops, 1);
		assertEquals(Nfs4.NFS4_OK, r.status);
		r.ok(Nfs4.OP_EXCHANGE_ID);
		Exchanged e = new Exchanged();
		e.clientid = r.in.readLong();
		e.sequence = r.in.readInt();
		e.flags = r.in.readInt();
		assertEquals(0, r.in.readInt());
		return e;
	}

	private static void channel(XdrEncoder a, int maxRequests) {
		a.writeInt(0).writeInt(65536).writeInt(65536).writeInt(8192).writeInt(16).writeInt(maxRequests).writeInt(0);
	}

	private static Ops createSession(long clientid, int sequence) {
		Ops ops = new Ops().op(Nfs4.OP_CREATE_SESSION);
		ops.args().writeLong(clientid).writeInt(sequence).writeInt(0);
		channel(ops.args(), 4);
		channel(ops.args(), 1);
		ops.args().writeInt(0x40000000).writeInt(1).writeInt(0);
		return ops;
	}

	private static byte[] session(TestClient c, long clientid, int sequence) throws IOException {
		Result r = c.compound(createSession(clientid, sequence), 1);
		assertEquals(Nfs4.NFS4_OK, r.status);
		r.ok(Nfs4.OP_CREATE_SESSION);
		return r.in.readFixedOpaque(Nfs4.NFS4_SESSIONID_SIZE);
	}

	private static Ops sequence(byte[] session, int seq, int slot, boolean cache) {
		Ops ops = new Ops().op(Nfs4.OP_SEQUENCE);
		ops.args().writeFixedOpaque(session).writeInt(seq).writeInt(slot).writeInt(slot).writeBoolean(cache);
		return ops;
	}

	private Ops makeFolder(byte[] session, int seq, int slot, String name) {
		Ops ops = sequence(session, seq, slot, true).putroot();
		ops.op(Nfs4.OP_CREATE);
		ops.args().writeInt(Nfs4.NF4DIR).writeString(name).writeIntArray(new int[0]).writeOpaque(new byte[0]);
		return ops;
	}

	@Test
	public void aClientGetsASessionAndUsesIt() throws Exception {
		TestClient c = client();
		Exchanged e = exchange(c, "client-a", (byte) 1);
		assertEquals(1, e.sequence);
		assertEquals(0, e.flags & 0x80000000);
		byte[] id = session(c, e.clientid, e.sequence);

		Result r = c.compound(sequence(id, 1, 0, false).putroot().getfh(), 1);
		assertEquals(Nfs4.NFS4_OK, r.status);
		r.ok(Nfs4.OP_SEQUENCE);
		assertArrayEquals(id, r.in.readFixedOpaque(Nfs4.NFS4_SESSIONID_SIZE));
		assertEquals(1, r.in.readInt());
		assertEquals(0, r.in.readInt());
		assertEquals(3, r.in.readInt());
		assertEquals(3, r.in.readInt());
		assertEquals(0, r.in.readInt());
		r.ok(Nfs4.OP_PUTROOTFH);
		r.ok(Nfs4.OP_GETFH);
	}

	@Test
	public void theSameClientNameAndVerifierIsTheSameClient() throws Exception {
		TestClient c = client();
		Exchanged first = exchange(c, "client-b", (byte) 1);
		session(c, first.clientid, first.sequence);
		Exchanged again = exchange(c, "client-b", (byte) 1);
		assertEquals(first.clientid, again.clientid);
		assertNotEquals(0, again.flags & 0x80000000);
		Exchanged rebooted = exchange(c, "client-b", (byte) 9);
		assertNotEquals(first.clientid, rebooted.clientid);
		assertEquals(0, rebooted.flags & 0x80000000);
	}

	@Test
	public void aCreateSessionSentAgainGetsTheSameSession() throws Exception {
		TestClient c = client();
		Exchanged e = exchange(c, "client-c", (byte) 1);
		byte[] id = session(c, e.clientid, 1);
		assertArrayEquals(id, session(c, e.clientid, 1));
		Result r = c.compound(createSession(e.clientid, 5), 1);
		assertEquals(Nfs4.NFS4ERR_SEQ_MISORDERED, r.status);
	}

	@Test
	public void aRequestSentAgainRunsOnce() throws Exception {
		TestClient c = client();
		Exchanged e = exchange(c, "client-d", (byte) 1);
		byte[] id = session(c, e.clientid, 1);

		Result r = c.compound(makeFolder(id, 1, 0, "once"), 1);
		assertEquals(Nfs4.NFS4_OK, r.status);
		assertTrue(root.getChild("once").isDirectory());

		// the same slot and sequence id, sent again with a new xid: the saved reply, not NFS4ERR_EXIST
		Result again = c.compound(makeFolder(id, 1, 0, "once"), 1);
		assertEquals(Nfs4.NFS4_OK, again.status);
		assertEquals(r.count, again.count);

		// the next sequence id runs, and the folder is there
		Result next = c.compound(makeFolder(id, 2, 0, "once"), 1);
		assertEquals(Nfs4.NFS4ERR_EXIST, next.status);
	}

	@Test
	public void aReplyThatWasNotKeptIsNotSentAgain() throws Exception {
		TestClient c = client();
		Exchanged e = exchange(c, "client-e", (byte) 1);
		byte[] id = session(c, e.clientid, 1);
		assertEquals(Nfs4.NFS4_OK, c.compound(sequence(id, 1, 0, false).putroot(), 1).status);
		assertEquals(Nfs4.NFS4ERR_RETRY_UNCACHED_REP, c.compound(sequence(id, 1, 0, false).putroot(), 1).status);
	}

	@Test
	public void sequenceIdsAndSlotsAreChecked() throws Exception {
		TestClient c = client();
		Exchanged e = exchange(c, "client-f", (byte) 1);
		byte[] id = session(c, e.clientid, 1);
		assertEquals(Nfs4.NFS4ERR_SEQ_MISORDERED, c.compound(sequence(id, 7, 0, false).putroot(), 1).status);
		assertEquals(Nfs4.NFS4ERR_BADSLOT, c.compound(sequence(id, 1, 40, false).putroot(), 1).status);
		byte[] unknown = id.clone();
		unknown[15] ^= 0x55;
		assertEquals(Nfs4.NFS4ERR_BADSESSION, c.compound(sequence(unknown, 1, 0, false).putroot(), 1).status);
	}

	@Test
	public void whatIsNotAllowedInASession() throws Exception {
		TestClient c = client();
		Exchanged e = exchange(c, "client-g", (byte) 1);
		byte[] id = session(c, e.clientid, 1);
		// an operation with no SEQUENCE before it
		Result r = c.compound(new Ops().putroot(), 1);
		assertEquals(Nfs4.NFS4ERR_OP_NOT_IN_SESSION, r.status);
		// SEQUENCE must be first
		Ops late = sequence(id, 1, 0, false).op(Nfs4.OP_SEQUENCE);
		late.args().writeFixedOpaque(id).writeInt(2).writeInt(0).writeInt(0).writeBoolean(false);
		assertEquals(Nfs4.NFS4ERR_SEQUENCE_POS, c.compound(late, 1).status);
		// NFSv4.0's client id operations aren't in 4.1
		Ops ops = sequence(id, 2, 0, false).op(Nfs4.OP_RENEW);
		ops.args().writeLong(e.clientid);
		Result renew = c.compound(ops, 1);
		assertEquals(Nfs4.NFS4ERR_OP_ILLEGAL, renew.status);
		// EXCHANGE_ID is alone in its COMPOUND
		Ops mixed = sequence(id, 3, 0, false).op(Nfs4.OP_EXCHANGE_ID);
		assertEquals(Nfs4.NFS4_OK, c.compound(new Ops().putroot(), 0).status);
		assertNotEquals(Nfs4.NFS4ERR_MINOR_VERS_MISMATCH, c.compound(mixed, 1).status);
	}

	@Test
	public void aDestroyedSessionIsGone() throws Exception {
		TestClient c = client();
		Exchanged e = exchange(c, "client-h", (byte) 1);
		byte[] id = session(c, e.clientid, 1);
		Ops destroy = new Ops().op(Nfs4.OP_DESTROY_SESSION);
		destroy.args().writeFixedOpaque(id);
		assertEquals(Nfs4.NFS4_OK, c.compound(destroy, 1).status);
		assertEquals(Nfs4.NFS4ERR_BADSESSION, c.compound(sequence(id, 1, 0, false).putroot(), 1).status);
		Ops again = new Ops().op(Nfs4.OP_DESTROY_SESSION);
		again.args().writeFixedOpaque(id);
		assertEquals(Nfs4.NFS4ERR_BADSESSION, c.compound(again, 1).status);
		// with its sessions gone the client id can go too
		Ops gone = new Ops().op(Nfs4.OP_DESTROY_CLIENTID);
		gone.args().writeLong(e.clientid);
		assertEquals(Nfs4.NFS4_OK, c.compound(gone, 1).status);
	}

	@Test
	public void aClientIdWithASessionCantBeDestroyed() throws Exception {
		TestClient c = client();
		Exchanged e = exchange(c, "client-i", (byte) 1);
		session(c, e.clientid, 1);
		Ops gone = new Ops().op(Nfs4.OP_DESTROY_CLIENTID);
		gone.args().writeLong(e.clientid);
		assertEquals(Nfs4.NFS4ERR_CLIENTID_BUSY, c.compound(gone, 1).status);
	}

	@Test
	public void reclaimCompleteHappensOnce() throws Exception {
		TestClient c = client();
		Exchanged e = exchange(c, "client-j", (byte) 1);
		byte[] id = session(c, e.clientid, 1);
		Ops first = sequence(id, 1, 0, false).op(Nfs4.OP_RECLAIM_COMPLETE);
		first.args().writeBoolean(false);
		assertEquals(Nfs4.NFS4_OK, c.compound(first, 1).status);
		Ops second = sequence(id, 2, 0, false).op(Nfs4.OP_RECLAIM_COMPLETE);
		second.args().writeBoolean(false);
		assertEquals(Nfs4.NFS4ERR_COMPLETE_ALREADY, c.compound(second, 1).status);
	}
}
