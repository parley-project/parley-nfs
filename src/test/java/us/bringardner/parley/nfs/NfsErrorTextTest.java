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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.nfs.client.NfsException;
import us.bringardner.parley.nfs.protocol.Nfs4;
import us.bringardner.parley.nfs.protocol.Nfs4Errors;

/** Errors are said in words (the viewer shows the message to a person). */
public class NfsErrorTextTest {

	@Test
	public void aMissingFileSaysSo() {
		NfsException e = new NfsException(Nfs4.NFS4ERR_NOENT, "open demo/a.txt");
		assertEquals("open demo/a.txt: no such file or directory (NFS4ERR_NOENT)", e.getMessage());
		assertEquals(Nfs4.NFS4ERR_NOENT, e.getStatus());
		assertTrue(e.isNotFound());
	}

	@Test
	public void aStatusWeDoNotKnowKeepsItsNumber() {
		assertEquals("lock x: unknown NFS status 12345", new NfsException(12345, "lock x").getMessage());
		assertEquals("12345", Nfs4Errors.name(12345));
	}

	@Test
	public void everyStatusOfTheProtocolHasWordsAndAName() throws Exception {
		Set<String> texts = new HashSet<String>();
		int count = 0;
		for (Field f : Nfs4.class.getFields()) {
			if( Modifier.isStatic(f.getModifiers()) && f.getType() == int.class && f.getName().startsWith("NFS4ERR_") ) {
				int status = f.getInt(null);
				assertFalse(Nfs4Errors.text(status).startsWith("unknown"), f.getName()+" has no text");
				assertEquals(f.getName(), Nfs4Errors.name(status));
				assertTrue(texts.add(Nfs4Errors.text(status)), f.getName()+" has the text of another status: "+Nfs4Errors.text(status));
				count++;
			}
		}
		assertTrue(count >= 60, "only "+count+" statuses found");
	}
}
