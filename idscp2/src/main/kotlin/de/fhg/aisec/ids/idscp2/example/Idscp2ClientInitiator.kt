package de.fhg.aisec.ids.idscp2.example

import de.fhg.aisec.ids.idscp2.default_drivers.daps.DefaultDapsDriver
import de.fhg.aisec.ids.idscp2.default_drivers.daps.DefaultDapsDriverConfig
import de.fhg.aisec.ids.idscp2.default_drivers.rat.dummy.RatProverDummy
import de.fhg.aisec.ids.idscp2.default_drivers.rat.dummy.RatVerifierDummy
import de.fhg.aisec.ids.idscp2.default_drivers.secure_channel.NativeTLSDriver
import de.fhg.aisec.ids.idscp2.idscp_core.api.idscp_connection.Idscp2Connection
import de.fhg.aisec.ids.idscp2.idscp_core.api.idscp_connection.Idscp2ConnectionAdapter
import de.fhg.aisec.ids.idscp2.idscp_core.api.idscp_connection.Idscp2ConnectionImpl
import de.fhg.aisec.ids.idscp2.idscp_core.api.configuration.Idscp2Configuration
import de.fhg.aisec.ids.idscp2.idscp_core.rat_registry.RatProverDriverRegistry
import de.fhg.aisec.ids.idscp2.idscp_core.rat_registry.RatVerifierDriverRegistry
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

class Idscp2ClientInitiator {
    private lateinit var connectionFuture: CompletableFuture<Idscp2Connection>

    fun init(configuration: Idscp2Configuration) {

        // create secure channel driver
        val secureChannelDriver = NativeTLSDriver<Idscp2Connection>()

        // create daps driver
        val dapsDriver = DefaultDapsDriver(DefaultDapsDriverConfig.Builder()
                .setKeyStorePath(configuration.keyStorePath)
                .setTrustStorePath(configuration.trustStorePath)
                .setKeyStorePassword(configuration.keyStorePassword)
                .setTrustStorePassword(configuration.trustStorePassword)
                .setKeyAlias(configuration.dapsKeyAlias)
                .setKeyPassword(configuration.keyPassword)
                .setDapsUrl("https://daps.aisec.fraunhofer.de")
                .build())

        // register rat drivers
        RatProverDriverRegistry.registerDriver(
                RatProverDummy.RAT_PROVER_DUMMY_ID, ::RatProverDummy, null)

        RatVerifierDriverRegistry.registerDriver(
                RatVerifierDummy.RAT_VERIFIER_DUMMY_ID, ::RatVerifierDummy, null)

        // connect to idscp2 server
        connectionFuture = secureChannelDriver.connect(::Idscp2ConnectionImpl, configuration, dapsDriver)
        connectionFuture.thenAccept { connection: Idscp2Connection ->
            println("Client: New connection with id " + connection.id)
            connection.addConnectionListener(object : Idscp2ConnectionAdapter() {
                override fun onError(t: Throwable) {
                    LOG.error("Client connection error occurred", t)
                }

                override fun onClose() {
                    LOG.info("Client: Connection with id " + connection.id + " has been closed")
                }
            })
            connection.addMessageListener { c: Idscp2Connection, data: ByteArray ->
                println("Received ping message: " + String(data, StandardCharsets.UTF_8))
                CompletableFuture.runAsync {
                    println("Close Connection")
                    c.close()
                } // FSM error if run from the same thread
            }
            connection.unlockMessaging()
            println("Send PING ...")
            connection.send("PING".toByteArray(StandardCharsets.UTF_8))
        }.exceptionally { t: Throwable? ->
            LOG.error("Client endpoint error occurred", t)
            null
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(Idscp2ClientInitiator::class.java)
    }
}