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
/**
 * The NFS version 4.0 server (RFC 7530): {@link us.bringardner.parley.nfs.server.NfsServer} on parley-net's NioServer, sharing one folder of any FileSource. It has byte-range locks (also as FileSource locks, decision D68), read and write delegations, ACLs, Kerberos (RPCSEC_GSS) and more than one export; named attributes, links, devices, sockets and fifos are not built.
 */
package us.bringardner.parley.nfs.server;
