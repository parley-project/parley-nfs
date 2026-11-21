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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/** The back channel of an NFSv4.1 session (RFC 8881 sections 18.33, 18.34 and 18.36), over raw XDR: what is granted and bound. */
public class NfsV41BackChannelTest {

	private static final String PREFIX = "test.nfs.v41back";
	private MemoryFileSourceFactory mem;
	private NfsServer server;
	private TestClient client;

	@BeforeEach
	public void start() throws IOException {
		System.setProperty(PREFIX+".delegations", "true");
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer(PREFIX, 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		client = new TestClient(server.getLocalPort());
	}

	@AfterEach
	public void stop() throws Exception {
		System.clearProperty(PREFIX+".delegations");
		client.close();
		server.stop();
		assertTrue(server.awaitStopped(10000));
		mem.disConnect();
	}

	private long exchange(String name) throws IOException {
		Ops ops = new Ops().op(Nfs4.OP_EXCHANGE_ID);
		ops.args().writeFixedOpaque(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
		ops.args().writeOpaque(name.getBytes(StandardCharsets.UTF_8));
		ops.args().writeInt(0).writeInt(0).writeInt(0);
		Result r = client.compound(ops, 1);
		r.ok(Nfs4.OP_EXCHANGE_ID);
		return r.in.readLong();
	}

	private static void channel(XdrEncoder a) {
		a.writeInt(0).writeInt(65536).writeInt(65536).writeInt(0).writeInt(8).writeInt(1).writeInt(0);
	}

	/** @return the flags the server granted, and the session id in the array's first element */
	private int createSession(long clientid, int flags, int program, byte[][] session) throws IOException {
		Ops ops = new Ops().op(Nfs4.OP_CREATE_SESSION);
		ops.args().writeLong(clientid).writeInt(1).writeInt(flags);
		channel(ops.args());
		channel(ops.args());
		ops.args().writeInt(program).writeInt(1).writeInt(0);
		Result r = client.compound(ops, 1);
		r.ok(Nfs4.OP_CREATE_SESSION);
		session[0] = r.in.readFixedOpaque(Nfs4.NFS4_SESSIONID_SIZE);
		r.in.readInt();
		return r.in.readInt();
	}

	private Result bind(byte[] session, int dir) throws IOException {
		Ops ops = new Ops().op(Nfs4.OP_BIND_CONN_TO_SESSION);
		ops.args().writeFixedOpaque(session).writeInt(dir).writeBoolean(false);
		return client.compound(ops, 1);
	}

	@Test
	public void aSessionThatAsksForABackChannelGetsIt() throws Exception {
		byte[][] id = new byte[1][];
		int flags = createSession(exchange("with-back"), 2, 0x40000000, id);
		assertEquals(2, flags & 2);
		// the connection that made the session is bound; asking again for both is granted, for the fore channel alone too
		Result both = bind(id[0], 3);
		assertEquals(Nfs4.NFS4_OK, both.status);
		both.ok(Nfs4.OP_BIND_CONN_TO_SESSION);
		assertArrayEquals(id[0], both.in.readFixedOpaque(Nfs4.NFS4_SESSIONID_SIZE));
		assertEquals(3, both.in.readInt());
		Result fore = bind(id[0], 1);
		fore.ok(Nfs4.OP_BIND_CONN_TO_SESSION);
		fore.in.readFixedOpaque(Nfs4.NFS4_SESSIONID_SIZE);
		assertEquals(1, fore.in.readInt());
		Result back = bind(id[0], 2);
		back.ok(Nfs4.OP_BIND_CONN_TO_SESSION);
		back.in.readFixedOpaque(Nfs4.NFS4_SESSIONID_SIZE);
		assertEquals(2, back.in.readInt());
	}

	@Test
	public void aSessionThatDidntAskHasNone() throws Exception {
		byte[][] id = new byte[1][];
		int flags = createSession(exchange("no-back"), 0, 0x40000000, id);
		assertEquals(0, flags & 2);
		assertEquals(Nfs4.NFS4ERR_INVAL, bind(id[0], 2).status);
		Result either = bind(id[0], 3);
		either.ok(Nfs4.OP_BIND_CONN_TO_SESSION);
		either.in.readFixedOpaque(Nfs4.NFS4_SESSIONID_SIZE);
		assertEquals(1, either.in.readInt());
	}

	@Test
	public void noCallbackProgramMeansNoBackChannel() throws Exception {
		byte[][] id = new byte[1][];
		assertEquals(0, createSession(exchange("no-program"), 2, 0, id) & 2);
	}

	@Test
	public void anUnknownSessionCantBeBound() throws Exception {
		byte[] unknown = new byte[Nfs4.NFS4_SESSIONID_SIZE];
		assertEquals(Nfs4.NFS4ERR_BADSESSION, bind(unknown, 1).status);
	}
}
