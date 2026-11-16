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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;

import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.MessageProp;
import org.ietf.jgss.Oid;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.permission.BasicParleyPermission;
import us.bringardner.parley.iam.permission.StandardPermissions;
import us.bringardner.parley.iam.registry.BasicUserRegistry;
import us.bringardner.parley.nfs.TestClient.Ops;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.RecordMarkingDecoder;
import us.bringardner.parley.nfs.rpc.Rpc;
import us.bringardner.parley.nfs.rpc.XdrDecoder;
import us.bringardner.parley.nfs.rpc.XdrEncoder;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.GssServer;
import us.bringardner.parley.nfs.server.NfsServer;

/**
 * RPCSEC_GSS with Kerberos (RFC 2203): the server over TCP, a Kerby KDC and a client built on
 * the JDK's JGSS, which does what the Linux client's rpc.gssd and kernel do.
 */
public class GssNfsTest {

	private static final String REALM = "EXAMPLE.COM";
	private static final String SERVER = "nfs/localhost@"+REALM;

	@TempDir
	static Path kdcDir;
	private static SimpleKdcServer kdc;
	private static String oldKrb5Conf;
	private static final Map<String, File> keytabs = new HashMap<String, File>();

	private MemoryFileSourceFactory mem;
	private NfsServer server;
	private Export export;
	private final List<Closeable> closeables = new ArrayList<Closeable>();
	private final List<String> properties = new ArrayList<String>();

	@BeforeAll
	public static void startKdc() throws Exception {
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		File dir = kdcDir.toFile();
		kdc = new SimpleKdcServer();
		kdc.setWorkDir(dir);
		kdc.setKdcRealm(REALM);
		kdc.setKdcHost("localhost");
		kdc.setKdcTcpPort(port);
		kdc.setAllowUdp(false);
		kdc.setAllowTcp(true);
		kdc.init();
		kdc.start();
		for (String p : new String[] { SERVER, "alice@"+REALM, "bob@"+REALM, "alice@OTHER.REALM" }) {
			if( p.endsWith("OTHER.REALM") ) {
				continue;
			}
			File k = new File(dir, p.replace('/', '_')+".keytab");
			kdc.createPrincipal(p, "secret");
			kdc.exportPrincipal(p, k);
			keytabs.put(p, k);
		}
		File conf = new File(dir, "krb5.conf");
		Files.write(conf.toPath(), ("[libdefaults]\n default_realm = "+REALM+"\n dns_lookup_kdc = false\n dns_lookup_realm = false\n"
				+" udp_preference_limit = 1\n rdns = false\n"
				+"[realms]\n "+REALM+" = {\n  kdc = localhost:"+port+"\n }\n").getBytes(StandardCharsets.UTF_8));
		oldKrb5Conf = System.getProperty("java.security.krb5.conf");
		System.setProperty("java.security.krb5.conf", conf.getAbsolutePath());
	}

	@AfterAll
	public static void stopKdc() throws Exception {
		if( kdc != null ) {
			kdc.stop();
		}
		if( oldKrb5Conf == null ) {
			System.clearProperty("java.security.krb5.conf");
		} else {
			System.setProperty("java.security.krb5.conf", oldKrb5Conf);
		}
	}

	@AfterEach
	public void tearDown() throws Exception {
		for (Closeable c : closeables) {
			c.close();
		}
		closeables.clear();
		if( server != null ) {
			server.stop();
			long end = System.currentTimeMillis()+10000;
			while( server.isRunning() && System.currentTimeMillis() < end ) {
				Thread.sleep(20);
			}
			assertFalse(server.isRunning());
			assertTrue(server.awaitStopped(10000));
		}
		if( mem != null ) {
			mem.disConnect();
		}
		for (String p : properties) {
			System.clearProperty(p);
		}
	}

	private void property(String name, String value) {
		System.setProperty(name, value);
		properties.add(name);
	}

	private void start(String sec, BasicUserRegistry registryOrNull) throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		export = new Export("test", root, false);
		property("test.nfs.gss.sec", sec);
		property("test.nfs.gss.krb5.principal", SERVER);
		property("test.nfs.gss.krb5.keytab", keytabs.get(SERVER).getAbsolutePath());
		server = new NfsServer("test.nfs.gss", 0);
		server.setExport(export);
		if( registryOrNull != null ) {
			server.setUserRegistry(registryOrNull);
		}
		server.startAndWait(10000);
	}

	private GssClient client(String principal, int service) throws Exception {
		GssClient c = new GssClient(server.getLocalPort(), principal, service);
		closeables.add(c);
		return c;
	}

	/** A reply: the accept status (or the negative auth_stat of a denial) and the procedure's results with their protection off. */
	private static final class Reply {
		int stat;
		XdrDecoder body;
	}

	/** The initiator: the JGSS context, and RPC calls protected the way the Linux client does. */
	private static final class GssClient implements Closeable {
		final Socket socket;
		final DataInputStream in;
		final OutputStream out;
		final Subject subject;
		final LoginContext login;
		final GSSContext ctx;
		final int service;
		int xid = 1;
		long seq = 0;
		byte[] handle;
		int window;

		GssClient(int port, String principal, int service) throws Exception {
			this.service = service;
			final Map<String, String> options = new HashMap<String, String>();
			options.put("principal", principal);
			options.put("keyTab", keytabs.get(principal).getAbsolutePath());
			options.put("useKeyTab", "true");
			options.put("storeKey", "true");
			options.put("isInitiator", "true");
			options.put("doNotPrompt", "true");
			Configuration conf = new Configuration() {
				@Override
				public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
					return new AppConfigurationEntry[] { new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
							AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options) };
				}
			};
			login = new LoginContext("test", new Subject(), null, conf);
			login.login();
			subject = login.getSubject();
			GSSManager m = GSSManager.getInstance();
			Oid krb5 = new Oid("1.2.840.113554.1.2.2");
			ctx = GssServer.runAs(subject, () -> {
				GSSContext c = m.createContext(m.createName(SERVER, GSSName.NT_USER_NAME), krb5, null, GSSContext.DEFAULT_LIFETIME);
				c.requestMutualAuth(true);
				c.requestConf(true);
				c.requestInteg(true);
				c.requestSequenceDet(false);
				c.requestReplayDet(false);
				return c;
			});
			socket = new Socket("127.0.0.1", port);
			socket.setSoTimeout(10000);
			in = new DataInputStream(socket.getInputStream());
			out = socket.getOutputStream();
		}

		@Override
		public void close() throws IOException {
			try {
				ctx.dispose();
				login.logout();
			} catch (Exception e) {
				// nothing more to do with them
			} finally {
				socket.close();
			}
		}

		private XdrEncoder header(int proc, int gssProc, long seqNumber, int svc, byte[] h) {
			XdrEncoder e = new XdrEncoder();
			e.writeInt(xid++).writeInt(Rpc.CALL).writeInt(Rpc.RPC_VERSION).writeInt(Nfs4.PROGRAM).writeInt(Nfs4.VERSION).writeInt(proc);
			e.writeInt(Rpc.RPCSEC_GSS);
			XdrEncoder cred = new XdrEncoder();
			cred.writeInt(1).writeInt(gssProc).writeUnsignedInt(seqNumber).writeInt(svc).writeOpaque(h);
			e.writeOpaque(cred.toByteArray());
			return e;
		}

		private void send(XdrEncoder call) throws IOException {
			java.nio.ByteBuffer f = RecordMarkingDecoder.frame(call);
			byte[] b = new byte[f.remaining()];
			f.get(b);
			out.write(b);
			out.flush();
		}

		private XdrDecoder readRecord() throws IOException {
			XdrEncoder rec = new XdrEncoder();
			while( true ) {
				int h = in.readInt();
				int len = h & ~RecordMarkingDecoder.LAST_FRAGMENT;
				byte[] b = new byte[len];
				in.readFully(b);
				rec.writeRaw(b, 0, len);
				if( (h & RecordMarkingDecoder.LAST_FRAGMENT) != 0 ) {
					return new XdrDecoder(rec.toByteArray());
				}
			}
		}

		/** INIT and CONTINUE_INIT until the context is complete. */
		void establish() throws Exception {
			byte[] token = new byte[0];
			int gssProc = 1;
			byte[] h = new byte[0];
			while( true ) {
				final byte[] in0 = token;
				byte[] out0 = GssServer.runAs(subject, () -> ctx.initSecContext(in0, 0, in0.length));
				XdrEncoder call = header(0, gssProc, 0, 1, h);
				call.writeInt(Rpc.AUTH_NONE).writeInt(0);
				call.writeOpaque(out0 == null ? new byte[0] : out0);
				send(call);
				XdrDecoder r = readRecord();
				assertEquals(xid-1, r.readInt());
				assertEquals(Rpc.REPLY, r.readInt());
				assertEquals(Rpc.MSG_ACCEPTED, r.readInt());
				int vf = r.readInt();
				byte[] verifier = r.readOpaque(400);
				assertEquals(Rpc.SUCCESS, r.readInt());
				handle = r.readOpaque(400);
				long major = r.readUnsignedInt();
				r.readUnsignedInt();
				window = r.readInt();
				token = r.readOpaque(100000);
				if( major == 0 ) {
					if( token.length > 0 ) {
						final byte[] t = token;
						GssServer.runAs(subject, () -> ctx.initSecContext(t, 0, t.length));
					}
					assertTrue(ctx.isEstablished());
					assertEquals(Rpc.RPCSEC_GSS, vf);
					byte[] w = { 0, 0, 0, (byte) window };
					ctx.verifyMIC(verifier, 0, verifier.length, w, 0, 4, new MessageProp(0, false));
					return;
				}
				assertEquals(GssServer.GSS_S_CONTINUE_NEEDED, (int) major, "the exchange failed");
				gssProc = 2;
				h = handle;
			}
		}

		private byte[] mic(byte[] data) throws Exception {
			return ctx.getMIC(data, 0, data.length, new MessageProp(0, false));
		}

		private static byte[] seqBytes(long s) {
			return new byte[] { (byte) (s >> 24), (byte) (s >> 16), (byte) (s >> 8), (byte) s };
		}

		/** Send a DATA call with the next sequence number; null when the server doesn't answer. */
		Reply call(int proc, XdrEncoder args) throws Exception {
			return call(proc, args, ++seq, false, handle);
		}

		Reply call(int proc, XdrEncoder args, long seqNumber, boolean breakVerifier, byte[] h) throws Exception {
			XdrEncoder call = header(proc, GssServer.PROC_DATA, seqNumber, service, h);
			int myXid = xid-1;
			byte[] head = call.toByteArray();
			byte[] m = mic(head);
			if( breakVerifier ) {
				m[m.length-1] ^= 1;
			}
			call.writeInt(Rpc.RPCSEC_GSS).writeOpaque(m);
			byte[] plain = args == null ? new byte[0] : args.toByteArray();
			byte[] data = new byte[4+plain.length];
			System.arraycopy(seqBytes(seqNumber), 0, data, 0, 4);
			System.arraycopy(plain, 0, data, 4, plain.length);
			switch (service) {
			case GssServer.SVC_INTEGRITY:
				call.writeOpaque(data).writeOpaque(mic(data));
				break;
			case GssServer.SVC_PRIVACY:
				call.writeOpaque(ctx.wrap(data, 0, data.length, new MessageProp(0, true)));
				break;
			default:
				call.writeRaw(plain, 0, plain.length);
			}
			send(call);
			XdrDecoder r;
			try {
				r = readRecord();
			} catch (SocketTimeoutException e) {
				return null;
			}
			assertEquals(myXid, r.readInt());
			assertEquals(Rpc.REPLY, r.readInt());
			Reply ret = new Reply();
			if( r.readInt() != Rpc.MSG_ACCEPTED ) {
				assertEquals(Rpc.AUTH_ERROR, r.readInt());
				ret.stat = -r.readInt();
				return ret;
			}
			int vf = r.readInt();
			byte[] verifier = r.readOpaque(400);
			ret.stat = r.readInt();
			if( ret.stat != Rpc.SUCCESS ) {
				return ret;
			}
			assertEquals(Rpc.RPCSEC_GSS, vf);
			byte[] sb = seqBytes(seqNumber);
			ctx.verifyMIC(verifier, 0, verifier.length, sb, 0, 4, new MessageProp(0, false));
			byte[] results;
			switch (service) {
			case GssServer.SVC_INTEGRITY: {
				byte[] d = r.readOpaque(1 << 20);
				byte[] mm = r.readOpaque(1000);
				ctx.verifyMIC(mm, 0, mm.length, d, 0, d.length, new MessageProp(0, false));
				assertArrayEquals(sb, java.util.Arrays.copyOf(d, 4));
				results = java.util.Arrays.copyOfRange(d, 4, d.length);
				break;
			}
			case GssServer.SVC_PRIVACY: {
				byte[] t = r.readOpaque(1 << 20);
				MessageProp p = new MessageProp(0, false);
				byte[] d = ctx.unwrap(t, 0, t.length, p);
				assertTrue(p.getPrivacy(), "the results were encrypted");
				assertArrayEquals(sb, java.util.Arrays.copyOf(d, 4));
				results = java.util.Arrays.copyOfRange(d, 4, d.length);
				break;
			}
			default:
				results = r.readFixedOpaque(r.remaining());
			}
			ret.body = new XdrDecoder(results);
			return ret;
		}

		/** A COMPOUND; the decoder is after the status, tag and count. */
		Reply compound(Ops ops) throws Exception {
			XdrEncoder a = new XdrEncoder();
			a.writeString("test").writeInt(0).writeInt(ops.count).write(ops.args());
			Reply r = call(Nfs4.PROC_COMPOUND, a);
			if( r != null && r.stat == Rpc.SUCCESS ) {
				r.stat = r.body.readInt();
				r.body.readOpaque(1024);
				r.body.readInt();
			}
			return r;
		}
	}

	private Ops getattr() {
		return new Ops().putroot().getattr(Nfs4.FATTR4_TYPE);
	}

	// ------------------------------------------------------------------ tests

	@Test
	public void eachServiceRunsACompound() throws Exception {
		start("krb5,krb5i,krb5p", null);
		for (int svc : new int[] { GssServer.SVC_NONE, GssServer.SVC_INTEGRITY, GssServer.SVC_PRIVACY }) {
			GssClient c = client("alice@"+REALM, svc);
			c.establish();
			assertEquals(GssServer.SEQ_WINDOW, c.window);
			Reply r = c.call(Nfs4.PROC_NULL, null);
			assertEquals(Rpc.SUCCESS, r.stat, "NULL with service "+svc);
			r = c.compound(getattr());
			assertEquals(Nfs4.NFS4_OK, r.stat, "COMPOUND with service "+svc);
		}
	}

	@Test
	public void sequenceNumbersAreNewAndInTheWindow() throws Exception {
		start("krb5", null);
		GssClient c = client("alice@"+REALM, GssServer.SVC_NONE);
		c.establish();
		c.socket.setSoTimeout(500);
		assertEquals(Nfs4.NFS4_OK, c.compound(getattr()).stat);
		// 1 was used: its repeat gets no answer
		assertEquals(null, c.call(Nfs4.PROC_NULL, null, 1, false, c.handle));
		// jump ahead, then a number still in the window is taken once
		assertEquals(Rpc.SUCCESS, c.call(Nfs4.PROC_NULL, null, 200, false, c.handle).stat);
		assertEquals(Rpc.SUCCESS, c.call(Nfs4.PROC_NULL, null, 150, false, c.handle).stat);
		assertEquals(null, c.call(Nfs4.PROC_NULL, null, 150, false, c.handle));
		// 72 is the oldest the window of 128 under 200 holds; 72 and below are dropped
		assertEquals(null, c.call(Nfs4.PROC_NULL, null, 72, false, c.handle));
		assertEquals(Rpc.SUCCESS, c.call(Nfs4.PROC_NULL, null, 73, false, c.handle).stat);
	}

	@Test
	public void aBrokenVerifierIsRefused() throws Exception {
		start("krb5", null);
		GssClient c = client("alice@"+REALM, GssServer.SVC_NONE);
		c.establish();
		assertEquals(-Rpc.AUTH_BADVERF, c.call(Nfs4.PROC_NULL, null, 1, true, c.handle).stat);
		// the repeat with a good verifier is still new
		assertEquals(Rpc.SUCCESS, c.call(Nfs4.PROC_NULL, null, 1, false, c.handle).stat);
	}

	@Test
	public void anUnknownContextIsAProblemWithTheCredential() throws Exception {
		start("krb5", null);
		GssClient c = client("alice@"+REALM, GssServer.SVC_NONE);
		c.establish();
		byte[] other = c.handle.clone();
		other[0] ^= 0x55;
		assertEquals(-Rpc.RPCSEC_GSS_CREDPROBLEM, c.call(Nfs4.PROC_NULL, null, 1, false, other).stat);
	}

	@Test
	public void destroyEndsTheContext() throws Exception {
		start("krb5", null);
		GssClient c = client("alice@"+REALM, GssServer.SVC_NONE);
		c.establish();
		assertEquals(Nfs4.NFS4_OK, c.compound(getattr()).stat);
		assertEquals(1, server.getGss().size());
		// DESTROY is a NULL call whose credential says so
		XdrEncoder call = c.header(0, GssServer.PROC_DESTROY, ++c.seq, GssServer.SVC_NONE, c.handle);
		byte[] verifier = c.mic(call.toByteArray());
		call.writeInt(Rpc.RPCSEC_GSS).writeOpaque(verifier);
		c.send(call);
		XdrDecoder r = c.readRecord();
		r.readInt();
		assertEquals(Rpc.REPLY, r.readInt());
		int replyStat = r.readInt();
		assertEquals(Rpc.MSG_ACCEPTED, replyStat, "denied: "+r.readInt()+" "+(r.remaining() >= 4 ? r.readInt() : -1));
		assertEquals(0, server.getGss().size());
		assertEquals(-Rpc.RPCSEC_GSS_CREDPROBLEM, c.call(Nfs4.PROC_NULL, null).stat);
	}

	@Test
	public void sysIsRefusedWhenItIsNotAllowed() throws Exception {
		start("krb5", null);
		TestClient t = new TestClient(server.getLocalPort());
		closeables.add(t);
		XdrDecoder r = t.call(Nfs4.PROGRAM, Nfs4.VERSION, 0, null);
		assertEquals(Rpc.MSG_DENIED, r.readInt());
		assertEquals(Rpc.AUTH_ERROR, r.readInt());
		assertEquals(Rpc.AUTH_TOOWEAK, r.readInt());
	}

	@Test
	public void aFlavorNotAllowedIsTooWeak() throws Exception {
		start("krb5p", null);
		GssClient c = client("alice@"+REALM, GssServer.SVC_INTEGRITY);
		c.establish();
		assertEquals(-Rpc.AUTH_TOOWEAK, c.call(Nfs4.PROC_NULL, null).stat);
		GssClient p = client("alice@"+REALM, GssServer.SVC_PRIVACY);
		p.establish();
		assertEquals(Rpc.SUCCESS, p.call(Nfs4.PROC_NULL, null).stat);
	}

	@Test
	public void secinfoListsTheFlavorsStrongestFirst() throws Exception {
		start("sys,krb5,krb5i,krb5p", null);
		FileSource f = export.getRoot().getChild("a");
		f.createNewFile();
		GssClient c = client("alice@"+REALM, GssServer.SVC_NONE);
		c.establish();
		Ops ops = new Ops().putroot().op(Nfs4.OP_SECINFO);
		ops.args().writeString("a");
		Reply r = c.compound(ops);
		assertEquals(Nfs4.NFS4_OK, r.stat);
		assertEquals(Nfs4.OP_PUTROOTFH, r.body.readInt());
		assertEquals(Nfs4.NFS4_OK, r.body.readInt());
		assertEquals(Nfs4.OP_SECINFO, r.body.readInt());
		assertEquals(Nfs4.NFS4_OK, r.body.readInt());
		int[] expected = { GssServer.SVC_PRIVACY, GssServer.SVC_INTEGRITY, GssServer.SVC_NONE };
		assertEquals(5, r.body.readInt());
		for (int service : expected) {
			assertEquals(Rpc.RPCSEC_GSS, r.body.readInt());
			byte[] oid = r.body.readOpaque(64);
			assertEquals(6, oid[0]);
			assertEquals(GssServer.KRB5_OID.length, oid[1]);
			assertArrayEquals(GssServer.KRB5_OID, java.util.Arrays.copyOfRange(oid, 2, oid.length));
			assertEquals(0, r.body.readInt());
			assertEquals(service, r.body.readInt());
		}
		assertEquals(Rpc.AUTH_SYS, r.body.readInt());
		// there is no user registry, so AUTH_NONE is taken too
		assertEquals(Rpc.AUTH_NONE, r.body.readInt());
	}

	@Test
	public void theUserIsThePrincipalWithoutItsRealm() throws Exception {
		BasicUserRegistry registry = new BasicUserRegistry(1000);
		start("krb5", registry);
		ParleyPrincipal alice = registry.createUser("alice");
		registry.grant(alice, new BasicParleyPermission(StandardPermissions.READ, export.getId()));
		registry.createUser("bob");

		GssClient a = client("alice@"+REALM, GssServer.SVC_NONE);
		a.establish();
		assertEquals(Nfs4.NFS4_OK, a.compound(getattr()).stat);

		// bob is a user but holds nothing
		GssClient b = client("bob@"+REALM, GssServer.SVC_NONE);
		b.establish();
		assertEquals(Nfs4.NFS4ERR_ACCESS, b.compound(getattr()).stat);

		// and alice may read, not write
		Ops mk = new Ops().putroot().op(Nfs4.OP_CREATE);
		mk.args().writeInt(Nfs4.NF4DIR).writeString("sub").writeIntArray(new int[0]).writeInt(0);
		assertEquals(Nfs4.NFS4ERR_ACCESS, a.compound(mk).stat);
	}

	@Test
	public void aSetupThatIsNotFinishedOrWrongIsRefusedNotFatal() throws Exception {
		start("krb5", null);
		GssClient c = client("alice@"+REALM, GssServer.SVC_NONE);
		// garbage as the token: the exchange fails with a status, the server goes on
		XdrEncoder call = c.header(0, GssServer.PROC_INIT, 0, 1, new byte[0]);
		call.writeInt(Rpc.AUTH_NONE).writeInt(0);
		call.writeOpaque(new byte[] { 1, 2, 3, 4, 5 });
		c.send(call);
		XdrDecoder r = c.readRecord();
		r.readInt();
		r.readInt();
		assertEquals(Rpc.MSG_ACCEPTED, r.readInt());
		r.readInt();
		r.readOpaque(400);
		assertEquals(Rpc.SUCCESS, r.readInt());
		assertEquals(0, r.readOpaque(400).length);
		long major = r.readUnsignedInt();
		assertTrue(major != 0 && major != GssServer.GSS_S_CONTINUE_NEEDED);
		assertEquals(0, server.getGss().size());
		// and a good exchange still works
		c.establish();
		assertEquals(Nfs4.NFS4_OK, c.compound(getattr()).stat);
	}

	@Test
	public void theKerberosSettingsAreChecked() throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource root = mem.createFileSource("/export");
		root.mkdirs();
		property("test.nfs.gss2.sec", "krb5");
		NfsServer s = new NfsServer("test.nfs.gss2", 0);
		s.setExport(new Export("test", root, false));
		IOException e = assertThrows(IOException.class, () -> s.startAndWait(10000));
		assertTrue(e.getMessage().contains("krb5.principal"), e.getMessage());
		System.setProperty("test.nfs.gss2.sec", "kerberos");
		e = assertThrows(IOException.class, () -> s.startAndWait(10000));
		assertTrue(e.getMessage().contains("kerberos"), e.getMessage());
		assertNotNull(e);
	}
}
