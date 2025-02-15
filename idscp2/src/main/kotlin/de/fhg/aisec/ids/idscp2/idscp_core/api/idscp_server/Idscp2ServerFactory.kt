/*-
 * ========================LICENSE_START=================================
 * idscp2
 * %%
 * Copyright (C) 2021 Fraunhofer AISEC
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package de.fhg.aisec.ids.idscp2.idscp_core.api.idscp_server

import de.fhg.aisec.ids.idscp2.idscp_core.api.Idscp2EndpointListener
import de.fhg.aisec.ids.idscp2.idscp_core.api.configuration.Idscp2Configuration
import de.fhg.aisec.ids.idscp2.idscp_core.api.idscp_connection.Idscp2Connection
import de.fhg.aisec.ids.idscp2.idscp_core.api.idscp_connection.Idscp2ConnectionAdapter
import de.fhg.aisec.ids.idscp2.idscp_core.drivers.SecureChannelDriver
import de.fhg.aisec.ids.idscp2.idscp_core.error.Idscp2Exception
import de.fhg.aisec.ids.idscp2.idscp_core.secure_channel.SecureChannel
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Idscp2ServerFactory class, provides IDSCP2 API to the User (Idscp2EndpointListener)
 *
 * @author Leon Beckmann (leon.beckmann@aisec.fraunhofer.de)
 */
class Idscp2ServerFactory<CC : Idscp2Connection, SecureChannelConfiguration>(
    private val connectionFactory: (SecureChannel, Idscp2Configuration) -> CC,
    private val endpointListener: Idscp2EndpointListener<CC>,
    private val configuration: Idscp2Configuration,
    private val secureChannelDriver: SecureChannelDriver<CC, SecureChannelConfiguration>,
    private val secureChannelConfig: SecureChannelConfiguration
) : SecureChannelInitListener<CC> {

    /**
     * User API to create a new IDSCP2 Server that starts a Secure Server that listens to new
     * secure channels
     */
    @Throws(Idscp2Exception::class)
    fun listen(): Idscp2Server<CC> {
        if (LOG.isInfoEnabled) {
            LOG.info("Starting new IDSCP2 server")
        }
        val serverListenerPromise = CompletableFuture<ServerConnectionListener<CC>>()
        val secureServer = secureChannelDriver.listen(this, serverListenerPromise, secureChannelConfig)
        val server = Idscp2Server<CC>(secureServer)
        serverListenerPromise.complete(server)
        return server
    }

    /**
     * A callback implementation to receive a new established secure channel from an Secure client/server.
     *
     * If the secure channel is null, no secure channel was established and an error is provided
     * to the user (or the error is ignored, in server case).
     *
     * If the secure channel was established, a new FSM is created for this connection and the
     * IDSCP2 handshake is started. After a successful handshake, a new Idscp2Connection is
     * created and provided to the user (and the IDSCP2 server).
     */
    @Synchronized
    override fun onSecureChannel(
        secureChannel: SecureChannel,
        serverListenerPromise: CompletableFuture<ServerConnectionListener<CC>>
    ) {
        if (LOG.isTraceEnabled) {
            LOG.trace("A new secure channel for an IDSCP2 connection was established")
        }
        // Threads calling onMessage() will be blocked until all listeners have been registered, see below
        val newConnection = connectionFactory(secureChannel, configuration)
        // Complete the connection promise for the IDSCP server
        serverListenerPromise.thenAccept { serverListener: ServerConnectionListener<CC> ->
            serverListener.onConnectionCreated(newConnection)
            newConnection.addConnectionListener(object : Idscp2ConnectionAdapter() {
                override fun onClose() {
                    serverListener.onConnectionClose(newConnection)
                }
            })
        }
        endpointListener.onConnection(newConnection)
        // Listeners have been applied in onConnection() callback above, so we can safely unlock messaging now
        newConnection.unlockMessaging()
    }

    override fun onError(t: Throwable) {
        endpointListener.onError(t)
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(Idscp2ServerFactory::class.java)
    }
}
