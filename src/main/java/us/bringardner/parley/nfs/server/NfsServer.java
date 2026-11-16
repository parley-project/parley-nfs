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
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.rpc.RecordMarkingDecoder;
import us.bringardner.parley.net.nio.NioServer;
import us.bringardner.parley.net.server.ServerMain;

/**
 * An NFS version 4.0 server (RFC 7530) on parley-net's non-blocking NioServer, sharing one
 * folder of any FileSource.
 * <p>
 * Settings, with the prefix {@value #PROPERTY_PREFIX}:
 * <ul>
 * <li>{@code port}: default {@value Nfs4#DEFAULT_PORT}</li>
 * <li>{@code export}: the folder to share, a path on the default FileSource factory or a
 * filesource: URL. It has no default: the installer must decide what is shared.</li>
 * <li>{@code exportName}: its name in logs and permission checks, default {@value #DEFAULT_EXPORT_NAME}</li>
 * <li>{@code readOnly}: true to refuse every change, default false</li>
 * <li>{@code exports}: instead of {@code export}, the names of several exports, comma separated; each
 * has {@code export.<name>} (the folder, as {@code export}) and optionally {@code export.<name>.readOnly}.
 * Clients then see a read-only folder of the exports (decisions D27 to D32)</li>
 * <li>{@code hideUnreadableExports}: true (the default) to list in that folder only the exports a client
 * may read, false to list them all</li>
 * <li>{@code domain}: the domain of owner names (user@domain), which must match the clients'
 * NFSv4 domain; default the DNS domain of this host's name, else {@value #DEFAULT_DOMAIN}</li>
 * <li>{@code leaseTime}: default 90s</li>
 * <li>{@code ioSize}: the most bytes one READ returns or one WRITE takes, default 1 MiB</li>
 * <li>{@code workerThreads}: threads that run requests (file access blocks), default {@value #DEFAULT_WORKER_THREADS}</li>
 * <li>{@code idmap}: properties file mapping AUTH_SYS uids to user names ({@link IdMap})</li>
 * <li>{@code anonymous}: the user that unmapped uids and AUTH_NONE are; unset refuses them</li>
 * <li>{@code authCacheTime}: how long a permission answer is kept, default 60s</li>
 * <li>{@code fileIdleTime}: how long an unused file stays open, default 30s</li>
 * </ul>
 * With a user registry (NioServer's provider setting, or {@link #setUserRegistry}) every request
 * is checked ({@link Authorizer}); without one the export is open to every client that can
 * connect, as a classic NFS export to a trusted network is (decision D5).
 *
 * @author Tony Bringardner
 */
public class NfsServer extends NioServer {

	public static final String PROPERTY_PREFIX = "parley.nfs";
	public static final String PROPERTY_EXPORT = "export";
	public static final String PROPERTY_EXPORT_NAME = "exportName";
	public static final String PROPERTY_EXPORTS = "exports";
	public static final String PROPERTY_HIDE_UNREADABLE = "hideUnreadableExports";
	public static final String PROPERTY_READ_ONLY = "readOnly";
	public static final String PROPERTY_DOMAIN = "domain";
	public static final String PROPERTY_LEASE_TIME = "leaseTime";
	public static final String PROPERTY_IO_SIZE = "ioSize";
	public static final String PROPERTY_WORKER_THREADS = "workerThreads";
	public static final String PROPERTY_IDMAP = "idmap";
	public static final String PROPERTY_ANONYMOUS = "anonymous";
	public static final String PROPERTY_AUTH_CACHE_TIME = "authCacheTime";
	public static final String PROPERTY_FILE_IDLE_TIME = "fileIdleTime";
	public static final String PROPERTY_REPLAY_CACHE_SIZE = "replayCacheSize";
	/** The security flavors taken, comma separated: sys, krb5, krb5i, krb5p (decision D34). */
	public static final String PROPERTY_SEC = "sec";
	public static final String PROPERTY_KRB5_PRINCIPAL = "krb5.principal";
	public static final String PROPERTY_KRB5_KEYTAB = "krb5.keytab";
	public static final String SEC_SYS = "sys";
	public static final String SEC_KRB5 = "krb5";
	public static final String SEC_KRB5I = "krb5i";
	public static final String SEC_KRB5P = "krb5p";
	/** The most RPCSEC_GSS contexts kept; the one idle longest goes when there are more. */
	static final int MAX_GSS_CONTEXTS = 4096;
	/** Names the settings file ({@link ServerMain}); default parley-nfs.properties in Parley's CONF folder. */
	public static final String CONFIG_PROPERTY = PROPERTY_PREFIX+".configFile";

	public static final String DEFAULT_EXPORT_NAME = "export";
	public static final String DEFAULT_DOMAIN = "localdomain";
	/** RFC 7530 leaves it to the server; 90 seconds is common. */
	public static final long DEFAULT_LEASE_TIME = 90_000;
	public static final int DEFAULT_IO_SIZE = 1024*1024;
	public static final int DEFAULT_WORKER_THREADS = 16;
	public static final long DEFAULT_AUTH_CACHE_TIME = 60_000;
	public static final long DEFAULT_FILE_IDLE_TIME = 30_000;
	public static final int DEFAULT_REPLAY_CACHE_SIZE = 1024;
	/** Room for the RPC and COMPOUND headers around the largest WRITE. */
	static final int RECORD_OVERHEAD = 64*1024;
	private static final int MAX_EXCLUSIVE = 10_000;

	/** the one export of a server that has no folder of exports */
	private volatile Export export;
	/** the exports of a server that has a folder of exports (null when it has one export) */
	private volatile List<Export> exports;
	/** the folder of exports (null when the server has one export) */
	private volatile Export pseudo;
	private volatile boolean hideUnreadable = true;
	private volatile StateManager stateManager;
	private final OpenFiles openFiles = new OpenFiles();
	private volatile Attributes attributes;
	private volatile Authorizer authorizer;
	/** startAndWait has read the settings, so the start() it calls doesn't */
	private boolean prepared;
	private volatile ReplayCache replayCache;
	private volatile int ioSize = DEFAULT_IO_SIZE;
	private volatile boolean authNoneAllowed = true;
	private volatile GssServer gss;
	private volatile java.util.Set<String> secs = Collections.singleton(SEC_SYS);
	private final byte[] writeVerifier = new byte[Nfs4.NFS4_VERIFIER_SIZE];
	private final Map<String, byte[]> exclusive = new LinkedHashMap<String, byte[]>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
			return size() > MAX_EXCLUSIVE;
		}
	};
	private volatile ThreadPoolExecutor workers;
	private volatile ScheduledExecutorService timer;
	private volatile Thread stopper;

	/**
	 * Run the server as a service or a standalone process ({@link ServerMain}).
	 */
	public static void main(String[] args) throws Exception {
		ServerMain.run("NfsServer", args, CONFIG_PROPERTY, "parley-nfs.properties",
				() -> java.util.Collections.singletonList(ServerMain.service(new NfsServer(), "NfsServer")));
	}

	/** A server with the {@value #PROPERTY_PREFIX} settings */
	public NfsServer() {
		this(PROPERTY_PREFIX, Nfs4.DEFAULT_PORT);
	}

	/**
	 * @param prefix the prefix of this server's settings
	 * @param defaultPort the port when {@code <prefix>.port} isn't set
	 */
	public NfsServer(String prefix, int defaultPort) {
		setPropertyPrefix(prefix);
		setName(prefix);
		setPort(getIntProperty(propertyName(PROPERTY_PORT), defaultPort));
		setHandlerFactory(() -> new NfsHandler(this));
		new SecureRandom().nextBytes(writeVerifier);
	}

	/** NFS has no login: every call carries its credential. */
	@Override
	protected boolean isLoginRequired() {
		return false;
	}

	/**
	 * Read the settings, then start.
	 * @throws IOException if they are wrong, or the server doesn't start
	 */
	@Override
	public void startAndWait(long timeoutMillis) throws IOException {
		prepare();
		// the start() that startAndWait calls must not read the settings and start the executors again
		prepared = true;
		super.startAndWait(timeoutMillis);
	}

	/** Read the settings, then start; a problem with them is logged and the server doesn't start. */
	@Override
	public synchronized void start() {
		if( prepared ) {
			prepared = false;
		} else {
			try {
				prepare();
			} catch (IOException e) {
				logError("Server "+getName()+" not started: "+e.getMessage(), e);
				return;
			}
		}
		super.start();
	}

	private synchronized void prepare() throws IOException {
		shutdownExecutors();
		if( export == null && exports == null ) {
			String names = getProperty(propertyName(PROPERTY_EXPORTS));
			if( names != null && !names.trim().isEmpty() ) {
				readExports(names);
			} else {
				String location = getProperty(propertyName(PROPERTY_EXPORT));
				if( location == null || location.trim().isEmpty() ) {
					throw new IOException(propertyName(PROPERTY_EXPORT)+" isn't set: it names the folder to share");
				}
				FileSource root = FileSourceFactory.getDefaultFactory().resolve(location.trim());
				export = new Export(getProperty(propertyName(PROPERTY_EXPORT_NAME), DEFAULT_EXPORT_NAME).trim(), root,
						getBooleanProperty(propertyName(PROPERTY_READ_ONLY), false));
			}
		}
		if( exports != null ) {
			arrange(exports);
		}
		for (Export e : getExports()) {
			if( !e.getRoot().isDirectory() ) {
				throw new IOException("The export "+e+" doesn't exist or isn't a folder");
			}
		}
		hideUnreadable = getBooleanProperty(propertyName(PROPERTY_HIDE_UNREADABLE), true);
		long lease = getDuration(propertyName(PROPERTY_LEASE_TIME), DEFAULT_LEASE_TIME);
		if( lease < 1000 ) {
			throw new IOException(propertyName(PROPERTY_LEASE_TIME)+" must be at least 1s");
		}
		ioSize = getIntProperty(propertyName(PROPERTY_IO_SIZE), DEFAULT_IO_SIZE);
		if( ioSize < 4096 ) {
			throw new IOException(propertyName(PROPERTY_IO_SIZE)+" must be at least 4096");
		}
		String domain = getProperty(propertyName(PROPERTY_DOMAIN));
		if( domain == null || domain.trim().isEmpty() ) {
			domain = defaultDomain();
		}
		IdMap idMap = null;
		String idmap = getProperty(propertyName(PROPERTY_IDMAP));
		if( idmap != null && !idmap.trim().isEmpty() ) {
			idMap = IdMap.load(FileSourceFactory.getDefaultFactory().resolve(idmap.trim()));
		}
		attributes = new Attributes(domain.trim(), idMap, lease/1000, ioSize, ioSize);
		stateManager = new StateManager((int) (System.currentTimeMillis()/1000), lease);
		int replays = getIntProperty(propertyName(PROPERTY_REPLAY_CACHE_SIZE), DEFAULT_REPLAY_CACHE_SIZE);
		replayCache = replays > 0 ? new ReplayCache(replays) : null;
		String anonymous = getProperty(propertyName(PROPERTY_ANONYMOUS));
		if( anonymous != null && anonymous.trim().isEmpty() ) {
			anonymous = null;
		}
		authorizer = new Authorizer(getUserRegistry(), idMap, anonymous == null ? null : anonymous.trim(),
				getName(), getDuration(propertyName(PROPERTY_AUTH_CACHE_TIME), DEFAULT_AUTH_CACHE_TIME),
				message -> logInfo(message));
		authNoneAllowed = !authorizer.isChecking() || anonymous != null;
		prepareSecurity();
		if( !authorizer.isChecking() ) {
			logWarn("Server "+getName()+": no user registry, so every client that can connect may use "+describeExports());
		}

		final int record = ioSize+RECORD_OVERHEAD;
		setDecoderFactory(() -> new RecordMarkingDecoder(record));
		if( getProperty(propertyName(PROPERTY_MAX_INPUT_BUFFER)) == null ) {
			setMaxInputBuffer(record+8);
		}
		int threads = getIntProperty(propertyName(PROPERTY_WORKER_THREADS), DEFAULT_WORKER_THREADS);
		ThreadPoolExecutor w = new ThreadPoolExecutor(Math.max(1, threads), Math.max(1, threads), 60, TimeUnit.SECONDS,
				new LinkedBlockingQueue<Runnable>(), threads(getName()+"-worker"));
		w.allowCoreThreadTimeOut(true);
		workers = w;
		setHandlerExecutor(w);
		long idle = getDuration(propertyName(PROPERTY_FILE_IDLE_TIME), DEFAULT_FILE_IDLE_TIME);
		ScheduledExecutorService t = Executors.newSingleThreadScheduledExecutor(threads(getName()+"-timer"));
		long period = Math.max(500, Math.min(lease/4, 5000));
		t.scheduleWithFixedDelay(() -> housekeeping(idle), period, period, TimeUnit.MILLISECONDS);
		timer = t;
		logInfo("Server "+getName()+" shares "+describeExports()+", lease "+lease/1000+"s, domain "+domain
				+(authorizer.isChecking() ? ", users checked by the registry" : ""));
	}

	private static ThreadFactory threads(String name) {
		AtomicInteger n = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, name+"-"+n.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}

	private static String defaultDomain() {
		try {
			String host = InetAddress.getLocalHost().getCanonicalHostName();
			int dot = host.indexOf('.');
			if( dot > 0 && dot < host.length()-1 && !Character.isDigit(host.charAt(host.length()-1)) ) {
				return host.substring(dot+1);
			}
		} catch (IOException e) {
			// the default
		}
		return DEFAULT_DOMAIN;
	}

	private void housekeeping(long idle) {
		try {
			long now = System.currentTimeMillis();
			StateManager s = stateManager;
			if( s != null ) {
				for (Node n : s.expire(now)) {
					if( !s.hasOpens(n) ) {
						openFiles.release(n);
					}
				}
			}
			// a stream opened for an OPEN for writing stays open while the file has one (D16)
			for (IOException e : openFiles.closeIdle(now, idle, n -> s != null && s.hasWriteOpen(n))) {
				logError("Closing an idle file failed", e);
			}
		} catch (IOException | RuntimeException e) {
			logError("NFS housekeeping failed", e);
		}
	}

	/**
	 * Stop accepting and close the connections (NioServer), then, once the server thread has
	 * closed them (their last calls run on the workers), stop the workers and close the files.
	 * Returns at once.
	 */
	@Override
	public void stop() {
		super.stop();
		Thread t = new Thread(() -> {
			long end = System.currentTimeMillis()+ServerMain.STOP_TIMEOUT;
			try {
				while( isRunning() && System.currentTimeMillis() < end ) {
					Thread.sleep(20);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			shutdownExecutors();
			GssServer g = gss;
			gss = null;
			if( g != null ) {
				g.close();
			}
			List<IOException> errors = openFiles.closeAll();
			for (IOException e : errors) {
				logError("Closing a file failed", e);
			}
		}, getName()+"-stop");
		t.setDaemon(true);
		stopper = t;
		t.start();
	}

	/**
	 * Wait until a {@link #stop()} has finished: the workers stopped and the files closed.
	 * @return false if it hadn't finished within the time
	 */
	public boolean awaitStopped(long millis) throws InterruptedException {
		Thread t = stopper;
		if( t != null ) {
			t.join(millis);
			return !t.isAlive();
		}
		return true;
	}

	private synchronized void shutdownExecutors() {
		ScheduledExecutorService t = timer;
		if( t != null ) {
			t.shutdownNow();
			timer = null;
		}
		ThreadPoolExecutor w = workers;
		if( w != null ) {
			w.shutdown();
			try {
				w.awaitTermination(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			workers = null;
		}
	}

	// ------------------------------------------------------------ for the handler and Compound

	/** @return the export of a server that has just one, else null */
	public Export getExport() {
		return export;
	}

	/** @param export the folder to share, instead of the one {@code <prefix>.export} names */
	public void setExport(Export export) {
		this.export = export;
		this.exports = null;
		this.pseudo = null;
	}

	/**
	 * Share several exports in a read-only folder of exports (decisions D27 to D32), instead of the
	 * ones {@code <prefix>.exports} names. Each export's name is its name in the folder.
	 */
	public void setExports(Collection<Export> exports) {
		this.export = null;
		this.pseudo = null;
		this.exports = new ArrayList<Export>(exports);
	}

	/** @return every export (the one export, or the ones in the folder of exports) */
	public List<Export> getExports() {
		List<Export> list = exports;
		if( list != null ) {
			return Collections.unmodifiableList(list);
		}
		return export == null ? Collections.<Export>emptyList() : Collections.singletonList(export);
	}

	/** @return what PUTROOTFH gives: the folder of exports, else the one export */
	public Export getRootExport() {
		Export p = pseudo;
		return p != null ? p : export;
	}

	/** @return the folder of exports, null when the server has one export */
	public Export getPseudoRoot() {
		return pseudo;
	}

	/** @return the export in the folder of exports with that name, or null */
	public Export findExport(String name) {
		for (Export e : getExports()) {
			if( e.getName().equals(name) ) {
				return e;
			}
		}
		return null;
	}

	/** @return the export (or the folder of exports) with that handle tag, or null */
	public Export findExport(int tag) {
		Export p = pseudo;
		if( p != null && p.getTag() == tag ) {
			return p;
		}
		for (Export e : getExports()) {
			if( e.isTagged() && e.getTag() == tag ) {
				return e;
			}
		}
		return null;
	}

	/**
	 * @return the node of a file handle, in the export its tag names
	 * @throws Nfs4Exception NFS4ERR_BADHANDLE if it isn't one of this server's handles, NFS4ERR_STALE
	 * for the handle of an export this server doesn't have
	 */
	public Node fromHandle(byte[] handle) throws Nfs4Exception {
		Integer tag = Export.handleTag(handle);
		Export e = tag == null ? export : findExport(tag);
		if( e == null ) {
			throw new Nfs4Exception(tag == null ? Nfs4.NFS4ERR_BADHANDLE : Nfs4.NFS4ERR_STALE, "no such export");
		}
		return e.fromHandle(handle);
	}

	/** @return true to list in the folder of exports only the exports a client may read */
	public boolean isHidingUnreadableExports() {
		return hideUnreadable;
	}

	private String describeExports() {
		Export one = export;
		if( one != null ) {
			return one.toString();
		}
		List<Export> list = getExports();
		StringBuilder b = new StringBuilder("exports");
		for (Export e : list) {
			b.append(b.length() == 7 ? " " : ", ").append(e);
		}
		return b.toString();
	}

	/** Make the exports from the {@code exports} setting. */
	private void readExports(String names) throws IOException {
		String single = getProperty(propertyName(PROPERTY_EXPORT));
		if( single != null && !single.trim().isEmpty() ) {
			throw new IOException(propertyName(PROPERTY_EXPORT)+" and "+propertyName(PROPERTY_EXPORTS)
				+" are both set: use one folder, or a list of names");
		}
		List<Export> list = new ArrayList<Export>();
		for (String n : names.split(",")) {
			String name = n.trim();
			if( name.isEmpty() ) {
				continue;
			}
			String key = propertyName(PROPERTY_EXPORT+"."+name);
			String location = getProperty(key);
			if( location == null || location.trim().isEmpty() ) {
				throw new IOException(key+" isn't set: it names the folder to share as "+name);
			}
			FileSource root = FileSourceFactory.getDefaultFactory().resolve(location.trim());
			list.add(new Export(name, root, getBooleanProperty(key+".readOnly", false)));
		}
		if( list.isEmpty() ) {
			throw new IOException(propertyName(PROPERTY_EXPORTS)+" names no export");
		}
		exports = list;
	}

	/** Check the names, give each export its handle tag and make the folder of exports (once). */
	private void arrange(List<Export> list) throws IOException {
		if( pseudo != null ) {
			return;
		}
		java.util.Set<String> names = new HashSet<String>();
		java.util.Set<Integer> tags = new HashSet<Integer>();
		for (Export e : list) {
			try {
				Export.checkName(e.getName());
			} catch (Nfs4Exception ex) {
				throw new IOException("The export name '"+e.getName()+"' can't be a folder name: "+ex.getMessage());
			}
			if( !names.add(e.getName()) ) {
				throw new IOException("There are two exports named "+e.getName());
			}
			int tag = (int) (NodeTable.hash(e.getName()) & 0x7fffffff);
			if( tag == 0 || !tags.add(tag) ) {
				throw new IOException("The export "+e.getName()+" has the same handle tag as another: rename one");
			}
		}
		Export p = Export.pseudoRoot(names);
		if( tags.contains(p.getTag()) ) {
			throw new IOException("An export has the handle tag of the folder of exports: rename it");
		}
		for (Export e : list) {
			e.tagged((int) (NodeTable.hash(e.getName()) & 0x7fffffff));
		}
		pseudo = p;
	}

	public StateManager getStateManager() {
		return stateManager;
	}

	public OpenFiles getOpenFiles() {
		return openFiles;
	}

	public Attributes getFileAttributes() {
		return attributes;
	}

	/** @return the duplicate request cache, or null when {@value #PROPERTY_REPLAY_CACHE_SIZE} is 0 */
	public ReplayCache getReplayCache() {
		return replayCache;
	}

	public Authorizer getAuthorizer() {
		return authorizer;
	}

	public int getMaxRead() {
		return ioSize;
	}

	/** Read the security flavors and, for Kerberos, log in as the server's principal (decisions D33 to D36). */
	private void prepareSecurity() throws IOException {
		GssServer old = gss;
		gss = null;
		if( old != null ) {
			old.close();
		}
		java.util.Set<String> set = new HashSet<String>();
		String list = getProperty(propertyName(PROPERTY_SEC), SEC_SYS);
		for (String s : list.split(",")) {
			s = s.trim().toLowerCase();
			if( s.isEmpty() ) {
				continue;
			}
			if( !s.equals(SEC_SYS) && !s.equals(SEC_KRB5) && !s.equals(SEC_KRB5I) && !s.equals(SEC_KRB5P) ) {
				throw new IOException(propertyName(PROPERTY_SEC)+" has "+s+": it is a list of sys, krb5, krb5i and krb5p");
			}
			set.add(s);
		}
		if( set.isEmpty() ) {
			throw new IOException(propertyName(PROPERTY_SEC)+" is empty: it is a list of sys, krb5, krb5i and krb5p");
		}
		if( set.contains(SEC_KRB5) || set.contains(SEC_KRB5I) || set.contains(SEC_KRB5P) ) {
			String principal = getProperty(propertyName(PROPERTY_KRB5_PRINCIPAL));
			String keytab = getProperty(propertyName(PROPERTY_KRB5_KEYTAB));
			if( principal == null || principal.trim().isEmpty() ) {
				throw new IOException(propertyName(PROPERTY_KRB5_PRINCIPAL)+" isn't set: it is the server's Kerberos principal, nfs/<host>@<REALM>");
			}
			if( keytab == null || keytab.trim().isEmpty() ) {
				throw new IOException(propertyName(PROPERTY_KRB5_KEYTAB)+" isn't set: it is the file with the server's keys");
			}
			gss = new GssServer(principal.trim(), keytab.trim(), MAX_GSS_CONTEXTS);
			logInfo("Server "+getName()+": Kerberos as "+principal.trim()+", realm "+gss.getRealm()+", flavors "+set);
		}
		secs = set;
	}

	/** @return true if the flavor (sys, krb5, krb5i or krb5p) is one the server takes */
	public boolean allowsSec(String flavor) {
		return secs.contains(flavor);
	}

	/** @return the Kerberos side, null when no krb5 flavor is taken */
	public GssServer getGss() {
		return gss;
	}

	/** @return true if AUTH_NONE calls are taken (no registry, or an anonymous user) */
	public boolean isAuthNoneAllowed() {
		return authNoneAllowed;
	}

	/**
	 * @return the verifier of unstable WRITEs: it changes when the server restarts, so clients
	 * know to send again what wasn't committed
	 */
	public byte[] getWriteVerifier() {
		return writeVerifier.clone();
	}

	/** @return true if path was made by an EXCLUSIVE4 OPEN with this verifier (a retransmission) */
	boolean isExclusiveCreate(String path, byte[] verifier) {
		synchronized (exclusive) {
			return Arrays.equals(exclusive.get(path), verifier);
		}
	}

	void setExclusiveCreate(String path, byte[] verifier) {
		synchronized (exclusive) {
			exclusive.put(path, verifier.clone());
		}
	}
}
