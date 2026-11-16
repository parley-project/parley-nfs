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
package us.bringardner.parley.nfs.server;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.PrivilegedExceptionAction;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;

import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.MessageProp;
import org.ietf.jgss.Oid;

/**
 * The server's end of RPCSEC_GSS with Kerberos V5 (RFC 2203, decisions D33 to D38): the server's login
 * from a keytab, the table of security contexts clients set up, and the checksums and encryption
 * of calls and replies, all with Java's own JGSS.
 * <p>
 * A context is made by INIT (and CONTINUE_INIT while the exchange takes more than one message), then
 * used by DATA calls, each with a sequence number that must be new and inside a window
 * ({@link #SEQ_WINDOW}), and ends with DESTROY or when its ticket expires.
 *
 * @author Tony Bringardner
 */
public final class GssServer {

	// gss_proc (RFC 2203 section 5)
	public static final int PROC_DATA = 0;
	public static final int PROC_INIT = 1;
	public static final int PROC_CONTINUE_INIT = 2;
	public static final int PROC_DESTROY = 3;

	// service (rpc_gss_service_t)
	public static final int SVC_NONE = 1;
	public static final int SVC_INTEGRITY = 2;
	public static final int SVC_PRIVACY = 3;

	// the status of INIT (RFC 2744 section 3.9.1: routine errors are in the high 16 bits)
	public static final int GSS_S_COMPLETE = 0;
	public static final int GSS_S_CONTINUE_NEEDED = 1 << 16;
	public static final int GSS_S_DEFECTIVE_TOKEN = 9 << 16;
	public static final int GSS_S_FAILURE = 13 << 16;

	/** The sequence numbers inside this many below the highest seen are accepted once each. */
	public static final int SEQ_WINDOW = 128;
	/** A sequence number this big or bigger ends the context (RFC 2203 section 5.3.3.1). */
	public static final long MAXSEQ = 0x80000000L;

	/** The OID of Kerberos V5 without its DER tag and length, as SECINFO sends it. */
	public static final byte[] KRB5_OID = { 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x12, 0x01, 0x02, 0x02 };

	/** A context that has not finished its set-up is forgotten after this long. */
	private static final long PENDING_MILLIS = 60_000;

	private final GSSManager manager = GSSManager.getInstance();
	private final Subject subject;
	private final LoginContext login;
	private final GSSCredential credential;
	private final String realm;
	private final String principal;
	private final int maxContexts;
	private final SecureRandom random = new SecureRandom();
	private final Map<String, Context> contexts = new HashMap<String, Context>();

	/**
	 * Log in as the server's principal from its keytab.
	 *
	 * @param principal for example nfs/server.example.com@EXAMPLE.COM
	 * @param keytab the file with its keys
	 * @param maxContexts the most contexts kept: the one idle longest goes when there are more
	 * @throws IOException if the login or the credential fails
	 */
	public GssServer(String principal, String keytab, int maxContexts) throws IOException {
		this.principal = principal;
		this.maxContexts = Math.max(1, maxContexts);
		final Map<String, String> options = new HashMap<String, String>();
		options.put("principal", principal);
		options.put("keyTab", keytab);
		options.put("useKeyTab", "true");
		options.put("storeKey", "true");
		options.put("isInitiator", "false");
		options.put("doNotPrompt", "true");
		Configuration conf = new Configuration() {
			@Override
			public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
				return new AppConfigurationEntry[] { new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
						AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options) };
			}
		};
		try {
			login = new LoginContext("parley-nfs", new Subject(), null, conf);
			login.login();
			subject = login.getSubject();
			Oid krb5 = new Oid("1.2.840.113554.1.2.2");
			credential = runAs(subject, () -> manager.createCredential(null, GSSCredential.INDEFINITE_LIFETIME, krb5,
					GSSCredential.ACCEPT_ONLY));
		} catch (LoginException | GSSException e) {
			throw new IOException("Kerberos login of "+principal+" with "+keytab+" failed: "+e.getMessage(), e);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Kerberos login of "+principal+" failed: "+e.getMessage(), e);
		}
		String r = null;
		for (KerberosPrincipal p : subject.getPrincipals(KerberosPrincipal.class)) {
			r = p.getRealm();
		}
		if( r == null ) {
			int at = principal.lastIndexOf('@');
			r = at < 0 ? "" : principal.substring(at+1);
		}
		this.realm = r;
	}

	/** Run an action as a Subject: Subject.callAs from Java 18, else Subject.doAs. */
	@SuppressWarnings("unchecked")
	public static <T> T runAs(Subject s, Callable<T> action) throws Exception {
		Method callAs;
		try {
			callAs = Subject.class.getMethod("callAs", Subject.class, Callable.class);
		} catch (NoSuchMethodException e) {
			callAs = null;
		}
		try {
			if( callAs != null ) {
				return (T) callAs.invoke(null, s, action);
			}
			return Subject.doAs(s, (PrivilegedExceptionAction<T>) action::call);
		} catch (InvocationTargetException e) {
			Throwable c = e.getCause();
			if( c instanceof CompletionException && c.getCause() != null ) {
				c = c.getCause();
			}
			if( c instanceof Exception ) {
				throw (Exception) c;
			}
			throw e;
		} catch (java.security.PrivilegedActionException e) {
			throw e.getException();
		}
	}

	/** @return the realm of the server's principal, which a user's name leaves out (decision D36) */
	public String getRealm() {
		return realm;
	}

	public String getPrincipal() {
		return principal;
	}

	/** @return the user a Kerberos principal is: its name without the realm when that is this server's own (decision D36) */
	public String userOf(String kerberosName) {
		int at = kerberosName.lastIndexOf('@');
		if( at >= 0 && kerberosName.substring(at+1).equals(realm) ) {
			return kerberosName.substring(0, at);
		}
		return kerberosName;
	}

	/** The answer to an INIT or CONTINUE_INIT. */
	public static final class InitResult {
		public final byte[] handle;
		public final int major;
		public final int minor;
		public final byte[] token;
		/** the context when the set-up is complete, else null */
		public final Context context;

		InitResult(byte[] handle, int major, int minor, byte[] token, Context context) {
			this.handle = handle;
			this.major = major;
			this.minor = minor;
			this.token = token;
			this.context = context;
		}
	}

	/**
	 * INIT (an empty handle) or CONTINUE_INIT: take the client's token.
	 *
	 * @param handle empty for a new context
	 */
	public InitResult init(byte[] handle, byte[] token) {
		long now = System.currentTimeMillis();
		Context c;
		if( handle.length == 0 ) {
			byte[] h = new byte[16];
			random.nextBytes(h);
			try {
				c = new Context(h, manager.createContext(credential), now);
			} catch (GSSException e) {
				return new InitResult(new byte[0], e.getMajor() << 16, e.getMinor(), new byte[0], null);
			}
			synchronized (contexts) {
				makeRoom();
				contexts.put(key(h), c);
			}
		} else {
			synchronized (contexts) {
				c = contexts.get(key(handle));
			}
			if( c == null || c.established ) {
				return new InitResult(handle, GSS_S_FAILURE, 0, new byte[0], null);
			}
		}
		synchronized (c) {
			try {
				final Context ctx = c;
				byte[] out = runAs(subject, () -> ctx.gss.acceptSecContext(token, 0, token.length));
				if( out == null ) {
					out = new byte[0];
				}
				if( !c.gss.isEstablished() ) {
					return new InitResult(c.handle, GSS_S_CONTINUE_NEEDED, 0, out, null);
				}
				String name = c.gss.getSrcName().toString();
				int life = c.gss.getLifetime();
				c.principal = name;
				c.user = userOf(name);
				c.expires = life <= 0 || life == GSSContext.INDEFINITE_LIFETIME ? Long.MAX_VALUE : now+life*1000L;
				c.established = true;
				return new InitResult(c.handle, GSS_S_COMPLETE, 0, out, c);
			} catch (GSSException e) {
				forget(c);
				return new InitResult(new byte[0], e.getMajor() << 16, e.getMinor(), new byte[0], null);
			} catch (Exception e) {
				forget(c);
				return new InitResult(new byte[0], GSS_S_FAILURE, 0, new byte[0], null);
			}
		}
	}

	/** @return the established context of a handle, or null if there is none (or it ended) */
	public Context find(byte[] handle) {
		long now = System.currentTimeMillis();
		synchronized (contexts) {
			Context c = contexts.get(key(handle));
			if( c == null || !c.established ) {
				return null;
			}
			if( c.expires < now ) {
				forget(c);
				return null;
			}
			c.lastUsed = now;
			return c;
		}
	}

	/** End a context (DESTROY). */
	public void destroy(Context c) {
		forget(c);
	}

	private void forget(Context c) {
		synchronized (contexts) {
			contexts.remove(key(c.handle));
		}
		try {
			c.gss.dispose();
		} catch (GSSException e) {
			// nothing more to do with it
		}
	}

	/** Drop the contexts whose tickets ended, and set-ups that never finished. */
	public int sweep(long now) {
		List<Context> gone = new ArrayList<Context>();
		synchronized (contexts) {
			for (Iterator<Context> i = contexts.values().iterator(); i.hasNext();) {
				Context c = i.next();
				if( c.established ? c.expires < now : c.created+PENDING_MILLIS < now ) {
					i.remove();
					gone.add(c);
				}
			}
		}
		for (Context c : gone) {
			try {
				c.gss.dispose();
			} catch (GSSException e) {
				// nothing more to do with it
			}
		}
		return gone.size();
	}

	/** @return how many contexts there are, set-ups not finished included */
	public int size() {
		synchronized (contexts) {
			return contexts.size();
		}
	}

	/** Make room for one more: the context idle longest goes. */
	private void makeRoom() {
		while( contexts.size() >= maxContexts ) {
			Context oldest = null;
			for (Context c : contexts.values()) {
				if( oldest == null || c.lastUsed < oldest.lastUsed ) {
					oldest = c;
				}
			}
			contexts.remove(key(oldest.handle));
		}
	}

	/** Log out of Kerberos. */
	public void close() {
		try {
			login.logout();
		} catch (LoginException e) {
			// the keys go with the object anyway
		}
	}

	private static String key(byte[] handle) {
		StringBuilder b = new StringBuilder(handle.length*2);
		for (byte x : handle) {
			b.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
		}
		return b.toString();
	}

	/** A security context a client set up: who it is, its sequence number window, its checksums and encryption. */
	public static final class Context {
		private final byte[] handle;
		private final GSSContext gss;
		private final long created;
		private volatile long lastUsed;
		private volatile long expires;
		private volatile boolean established;
		private String principal;
		private String user;
		private long maxSeq = -1;
		private final BitSet seen = new BitSet(SEQ_WINDOW);

		Context(byte[] handle, GSSContext gss, long now) {
			this.handle = handle;
			this.gss = gss;
			this.created = now;
			this.lastUsed = now;
		}

		public byte[] getHandle() {
			return handle.clone();
		}

		/** @return the client's Kerberos principal, alice@EXAMPLE.COM */
		public String getPrincipal() {
			return principal;
		}

		/** @return the user it is for the registry (decision D36) */
		public String getUser() {
			return user;
		}

		/**
		 * Take a call's sequence number: it must be new and not below the window (RFC 2203 section 5.3.3.1).
		 *
		 * @return false if the call is to be dropped
		 */
		public synchronized boolean acceptSeq(long seq) {
			if( seq >= MAXSEQ ) {
				return false;
			}
			if( maxSeq < 0 || seq > maxSeq ) {
				if( maxSeq < 0 || seq-maxSeq >= SEQ_WINDOW ) {
					seen.clear();
				} else {
					for (long n = maxSeq+1; n <= seq; n++) {
						seen.clear((int) (n % SEQ_WINDOW));
					}
				}
				maxSeq = seq;
				seen.set((int) (seq % SEQ_WINDOW));
				return true;
			}
			if( seq <= maxSeq-SEQ_WINDOW ) {
				return false;
			}
			int bit = (int) (seq % SEQ_WINDOW);
			if( seen.get(bit) ) {
				return false;
			}
			seen.set(bit);
			return true;
		}

		/** @return the checksum (MIC) of the data */
		public synchronized byte[] mic(byte[] data) throws GSSException {
			return gss.getMIC(data, 0, data.length, new MessageProp(0, false));
		}

		/** @return true if the checksum is the data's */
		public synchronized boolean verifyMic(byte[] data, byte[] mic) {
			try {
				gss.verifyMIC(mic, 0, mic.length, data, 0, data.length, new MessageProp(0, false));
				return true;
			} catch (GSSException e) {
				return false;
			}
		}

		/** @return the data encrypted, with its checksum */
		public synchronized byte[] wrap(byte[] data) throws GSSException {
			return gss.wrap(data, 0, data.length, new MessageProp(0, true));
		}

		/**
		 * @return the data of an encrypted token
		 * @throws GSSException if it doesn't check, or wasn't encrypted
		 */
		public synchronized byte[] unwrap(byte[] token) throws GSSException {
			MessageProp prop = new MessageProp(0, false);
			byte[] ret = gss.unwrap(token, 0, token.length, prop);
			if( !prop.getPrivacy() ) {
				throw new GSSException(GSSException.BAD_MIC, 0, "not encrypted");
			}
			return ret;
		}
	}
}
