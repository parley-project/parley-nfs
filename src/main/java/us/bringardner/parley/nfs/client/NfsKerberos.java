package us.bringardner.parley.nfs.client;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.MessageProp;
import org.ietf.jgss.Oid;

import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.Rpc;
import us.bringardner.parley.nfs.rpc.XdrDecoder;
import us.bringardner.parley.nfs.rpc.XdrEncoder;
import us.bringardner.parley.nfs.server.GssServer;

/**
 * How an {@link NfsClient} proves who its user is with Kerberos (RPCSEC_GSS, RFC 2203, RFC 7532): who the user is and
 * what protection the calls get (decision D84). Give it to the client's constructor; the client makes one
 * security context for each connection it makes. Close it after the client when it logged in itself.
 */
public class NfsKerberos implements Closeable {

	/** What a call and its reply are protected with. */
	public enum Protection {
		/** krb5: the call's header is signed, the arguments and results are not. */
		AUTHENTICATION(GssServer.SVC_NONE),
		/** krb5i: the arguments and results are signed too. */
		INTEGRITY(GssServer.SVC_INTEGRITY),
		/** krb5p: the arguments and results are encrypted. */
		PRIVACY(GssServer.SVC_PRIVACY);

		final int service;

		Protection(int service) {
			this.service = service;
		}
	}

	private static final String KRB5_LOGIN = "com.sun.security.auth.module.Krb5LoginModule";

	private final String servicePrincipal;
	private final Subject subject;
	private final LoginContext login;
	private final Protection protection;

	/**
	 * The user whose Kerberos credentials are already in a Subject, or null for the ones JGSS finds itself (the
	 * ticket cache of kinit).
	 * @param servicePrincipal the server's name: "nfs@host" or "nfs/host@REALM"
	 */
	public NfsKerberos(String servicePrincipal, Subject subject, Protection protection) {
		this(servicePrincipal, subject, null, protection);
	}

	private NfsKerberos(String servicePrincipal, Subject subject, LoginContext login, Protection protection) {
		this.servicePrincipal = servicePrincipal;
		this.subject = subject;
		this.login = login;
		this.protection = protection;
	}

	/**
	 * Log in a user with a key table.
	 * @param principal the user, "name@REALM"
	 * @param keytab the user's keys
	 */
	public static NfsKerberos withKeytab(String servicePrincipal, String principal, File keytab, Protection protection) throws IOException {
		final Map<String, String> options = new HashMap<String, String>();
		options.put("principal", principal);
		options.put("keyTab", keytab.getAbsolutePath());
		options.put("useKeyTab", "true");
		options.put("storeKey", "true");
		options.put("isInitiator", "true");
		options.put("doNotPrompt", "true");
		Configuration conf = new Configuration() {
			@Override
			public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
				return new AppConfigurationEntry[] { new AppConfigurationEntry(KRB5_LOGIN,
						AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options) };
			}
		};
		try {
			LoginContext lc = new LoginContext("parley-nfs-client", new Subject(), null, conf);
			lc.login();
			return new NfsKerberos(servicePrincipal, lc.getSubject(), lc, protection);
		} catch (javax.security.auth.login.LoginException e) {
			throw new IOException("Kerberos login of "+principal+" failed: "+e.getMessage(), e);
		}
	}

	public Protection getProtection() {
		return protection;
	}

	@Override
	public void close() {
		if( login != null ) {
			try {
				login.logout();
			} catch (javax.security.auth.login.LoginException e) {
				// nothing more to do with it
			}
		}
	}

	/** The security context of one connection. */
	static final class Session {
		private static final int AUTH_CREDPROBLEM = 13;
		private static final int AUTH_CTXPROBLEM = 14;

		private final NfsKerberos owner;
		private final GSSContext ctx;
		private final int service;
		private byte[] handle = new byte[0];
		private long seq;
		private int window;

		/** What sends one RPC call and returns the reply after its xid and the REPLY mark. */
		interface Wire {
			/** @return an xid for the next call */
			int nextXid();

			XdrDecoder exchange(int xid, XdrEncoder call) throws IOException;
		}

		Session(NfsKerberos owner) throws IOException {
			this.owner = owner;
			this.service = owner.protection.service;
			try {
				GSSManager m = GSSManager.getInstance();
				Oid krb5 = new Oid("1.2.840.113554.1.2.2");
				ctx = run(() -> {
					GSSContext c = m.createContext(m.createName(owner.servicePrincipal, nameType(owner.servicePrincipal)), krb5, null, GSSContext.DEFAULT_LIFETIME);
					c.requestMutualAuth(true);
					c.requestConf(true);
					c.requestInteg(true);
					c.requestSequenceDet(false);
					c.requestReplayDet(false);
					return c;
				});
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				throw new IOException("Kerberos: "+e.getMessage(), e);
			}
		}

		/** "nfs@host" is a host based name; "nfs/host@REALM" is a Kerberos principal. */
		private static Oid nameType(String name) throws GSSException {
			return name.contains("/") ? new Oid("1.2.840.113554.1.2.2.1") : GSSName.NT_HOSTBASED_SERVICE;
		}

		private <T> T run(java.util.concurrent.Callable<T> action) throws Exception {
			if( owner.subject == null ) {
				return action.call();
			}
			return GssServer.runAs(owner.subject, action);
		}

		private XdrEncoder header(int xid, int proc, int gssProc, long seqNumber) {
			XdrEncoder e = new XdrEncoder();
			e.writeInt(xid).writeInt(Rpc.CALL).writeInt(Rpc.RPC_VERSION).writeInt(Nfs4.PROGRAM).writeInt(Nfs4.VERSION).writeInt(proc);
			e.writeInt(Rpc.RPCSEC_GSS);
			XdrEncoder cred = new XdrEncoder();
			cred.writeInt(1).writeInt(gssProc).writeUnsignedInt(seqNumber).writeInt(gssProc == GssServer.PROC_DATA || gssProc == GssServer.PROC_DESTROY ? service : GssServer.SVC_NONE).writeOpaque(handle);
			e.writeOpaque(cred.toByteArray());
			return e;
		}

		/** INIT and CONTINUE_INIT until the context is complete. */
		void establish(Wire wire) throws IOException {
			try {
				byte[] token = new byte[0];
				int gssProc = GssServer.PROC_INIT;
				while( true ) {
					int xid = wire.nextXid();
					final byte[] in = token;
					byte[] out = run(() -> ctx.initSecContext(in, 0, in.length));
					XdrEncoder call = header(xid, 0, gssProc, 0);
					call.writeInt(Rpc.AUTH_NONE).writeInt(0);
					call.writeOpaque(out == null ? new byte[0] : out);
					XdrDecoder r = wire.exchange(xid, call);
					if( r.readInt() != Rpc.MSG_ACCEPTED ) {
						throw new IOException("the server refused the Kerberos exchange");
					}
					int verifierFlavor = r.readInt();
					byte[] verifier = r.readOpaque(Rpc.MAX_AUTH_BYTES);
					int accept = r.readInt();
					if( accept != Rpc.SUCCESS ) {
						throw new IOException("the server didn't accept the Kerberos exchange: accept_stat "+accept);
					}
					handle = r.readOpaque(Rpc.MAX_AUTH_BYTES);
					long major = r.readUnsignedInt();
					long minor = r.readUnsignedInt();
					window = r.readInt();
					token = r.readOpaque(1 << 20);
					if( major == GssServer.GSS_S_COMPLETE ) {
						if( token.length > 0 ) {
							final byte[] t = token;
							run(() -> ctx.initSecContext(t, 0, t.length));
						}
						if( !ctx.isEstablished() ) {
							throw new IOException("the Kerberos context is not complete");
						}
						if( verifierFlavor == Rpc.RPCSEC_GSS ) {
							ctx.verifyMIC(verifier, 0, verifier.length, seqBytes(window), 0, 4, new MessageProp(0, false));
						}
						seq = 0;
						return;
					}
					if( major != GssServer.GSS_S_CONTINUE_NEEDED ) {
						throw new IOException("the server refused the Kerberos login (major status "+major+", minor "+minor+")");
					}
					gssProc = GssServer.PROC_CONTINUE_INIT;
				}
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				throw new IOException("Kerberos: "+e.getMessage(), e);
			}
		}

		private static byte[] seqBytes(long s) {
			return new byte[] { (byte) (s >> 24), (byte) (s >> 16), (byte) (s >> 8), (byte) s };
		}

		/** @return the sequence number of the call that dataCall makes next */
		synchronized long nextSeq() throws IOException {
			if( seq + 1 >= GssServer.MAXSEQ ) {
				throw new SocketException("the Kerberos context has used all its sequence numbers");
			}
			return ++seq;
		}

		/** A DATA call, signed and, as the protection asks, with its arguments signed or encrypted. */
		XdrEncoder dataCall(int xid, int proc, XdrEncoder args, long seqNumber) throws IOException {
			try {
				XdrEncoder call = header(xid, proc, GssServer.PROC_DATA, seqNumber);
				byte[] head = call.toByteArray();
				call.writeInt(Rpc.RPCSEC_GSS).writeOpaque(ctx.getMIC(head, 0, head.length, new MessageProp(0, false)));
				byte[] plain = args.toByteArray();
				switch (service) {
				case GssServer.SVC_INTEGRITY: {
					byte[] data = withSeq(seqNumber, plain);
					call.writeOpaque(data).writeOpaque(ctx.getMIC(data, 0, data.length, new MessageProp(0, false)));
					break;
				}
				case GssServer.SVC_PRIVACY: {
					byte[] data = withSeq(seqNumber, plain);
					call.writeOpaque(ctx.wrap(data, 0, data.length, new MessageProp(0, true)));
					break;
				}
				default:
					call.writeRaw(plain, 0, plain.length);
				}
				return call;
			} catch (GSSException e) {
				throw new IOException("Kerberos: "+e.getMessage(), e);
			}
		}

		private static byte[] withSeq(long seqNumber, byte[] plain) {
			byte[] data = new byte[4+plain.length];
			System.arraycopy(seqBytes(seqNumber), 0, data, 0, 4);
			System.arraycopy(plain, 0, data, 4, plain.length);
			return data;
		}

		/** A denied call: the context is gone (the server restarted, forgot it) means a new connection and context. */
		void denied(XdrDecoder r) throws IOException {
			int reject = r.readInt();
			if( reject == 1 ) {
				int stat = r.readInt();
				if( stat == AUTH_CREDPROBLEM || stat == AUTH_CTXPROBLEM ) {
					throw new SocketException("the server no longer has the Kerberos context");
				}
				throw new IOException("the server denied the call: auth_stat "+stat);
			}
			throw new IOException("the server denied the call: "+reject);
		}

		/**
		 * The results of an accepted call.
		 * @param verifier the reply's verifier
		 * @param r after the accept status
		 * @return the results, with their protection taken off
		 */
		XdrDecoder results(long seqNumber, byte[] verifier, XdrDecoder r) throws IOException {
			try {
				byte[] sb = seqBytes(seqNumber);
				ctx.verifyMIC(verifier, 0, verifier.length, sb, 0, 4, new MessageProp(0, false));
				switch (service) {
				case GssServer.SVC_INTEGRITY: {
					byte[] d = r.readOpaque(1 << 24);
					byte[] mm = r.readOpaque(Rpc.MAX_AUTH_BYTES);
					ctx.verifyMIC(mm, 0, mm.length, d, 0, d.length, new MessageProp(0, false));
					return strip(sb, d);
				}
				case GssServer.SVC_PRIVACY: {
					byte[] t = r.readOpaque(1 << 24);
					MessageProp p = new MessageProp(0, false);
					byte[] d = ctx.unwrap(t, 0, t.length, p);
					if( !p.getPrivacy() ) {
						throw new IOException("the server's results were not encrypted");
					}
					return strip(sb, d);
				}
				default:
					return r;
				}
			} catch (GSSException e) {
				throw new IOException("Kerberos: "+e.getMessage(), e);
			}
		}

		private static XdrDecoder strip(byte[] sb, byte[] d) throws IOException {
			if( d.length < 4 || !Arrays.equals(sb, Arrays.copyOf(d, 4)) ) {
				throw new IOException("the reply's sequence number is wrong");
			}
			return new XdrDecoder(Arrays.copyOfRange(d, 4, d.length));
		}

		/** End the context, which the server then forgets; failing to is no matter. */
		void destroy(Wire wire) {
			try {
				long s = nextSeq();
				int xid = wire.nextXid();
				XdrEncoder call = header(xid, 0, GssServer.PROC_DESTROY, s);
				byte[] head = call.toByteArray();
				call.writeInt(Rpc.RPCSEC_GSS).writeOpaque(ctx.getMIC(head, 0, head.length, new MessageProp(0, false)));
				wire.exchange(xid, call);
			} catch (IOException | GSSException e) {
				// the connection is being closed anyway
			} finally {
				try {
					ctx.dispose();
				} catch (GSSException e) {
					// nothing more to do with it
				}
			}
		}
	}
}
