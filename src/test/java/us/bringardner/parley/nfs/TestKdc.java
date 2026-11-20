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

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;

/**
 * One Kerby KDC for the whole test JVM. The JDK reads krb5.conf once, so each test class starting its own KDC on
 * another port would find the first one's address.
 */
final class TestKdc {

	static final String REALM = "EXAMPLE.COM";
	static final String SERVER = "nfs/localhost@"+REALM;
	static final Map<String, File> KEYTABS = new HashMap<String, File>();
	private static boolean started;

	private TestKdc() {
	}

	/** Start the KDC if no test class has yet. */
	static synchronized void start(String... users) throws Exception {
		if( started ) {
			return;
		}
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		Path dirPath = Files.createTempDirectory("parley-nfs-kdc");
		File dir = dirPath.toFile();
		SimpleKdcServer kdc = new SimpleKdcServer();
		kdc.setWorkDir(dir);
		kdc.setKdcRealm(REALM);
		kdc.setKdcHost("localhost");
		kdc.setKdcTcpPort(port);
		kdc.setAllowUdp(false);
		kdc.setAllowTcp(true);
		kdc.init();
		kdc.start();
		for (String p : new String[] { SERVER, "alice@"+REALM, "bob@"+REALM }) {
			File k = new File(dir, p.replace('/', '_')+".keytab");
			kdc.createPrincipal(p, "secret");
			kdc.exportPrincipal(p, k);
			KEYTABS.put(p, k);
		}
		File conf = new File(dir, "krb5.conf");
		Files.write(conf.toPath(), ("[libdefaults]\n default_realm = "+REALM+"\n dns_lookup_kdc = false\n dns_lookup_realm = false\n"
				+" udp_preference_limit = 1\n rdns = false\n"
				+"[realms]\n "+REALM+" = {\n  kdc = localhost:"+port+"\n }\n").getBytes(StandardCharsets.UTF_8));
		System.setProperty("java.security.krb5.conf", conf.getAbsolutePath());
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				kdc.stop();
				try (java.util.stream.Stream<Path> w = Files.walk(dirPath)) {
					w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
				}
			} catch (Exception | Error e) {
				// the JVM is ending
			}
		}));
		started = true;
	}
}
