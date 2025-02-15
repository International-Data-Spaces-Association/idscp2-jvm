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
package de.fhg.aisec.ids.idscp2.idscp_core.rat_registry

import de.fhg.aisec.ids.idscp2.idscp_core.drivers.RatVerifierDriver
import de.fhg.aisec.ids.idscp2.idscp_core.fsm.fsmListeners.RatVerifierFsmListener
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * A Rat Verifier Driver Registry
 * The User can register Driver implementation instances and its configurations to the registry
 *
 *
 * The Idscpv2 protocol will select during the idscp handshake a Rat Verifier mechanism and will
 * check for this RatVerifier in this registry
 *
 * @author Leon Beckmann (leon.beckmann@aisec.fraunhofer.de)
 */
object RatVerifierDriverRegistry {
    private val LOG by lazy { LoggerFactory.getLogger(RatVerifierDriverRegistry::class.java) }

    /**
     * An inner static wrapper class, that wraps driver config and driver class
     */
    private class DriverWrapper<VC>(
        val driverFactory: (RatVerifierFsmListener) -> RatVerifierDriver<VC>,
        val driverConfig: VC?
    ) {
        fun getInstance(listener: RatVerifierFsmListener) = driverFactory.invoke(listener).also { d ->
            driverConfig?.let { d.setConfig(it) }
        }
    }

    private val drivers = ConcurrentHashMap<String, DriverWrapper<*>>()

    /**
     * Register Rat Verifier driver and an optional configuration in the registry
     */
    fun <VC> registerDriver(
        instance: String,
        driverFactory: (RatVerifierFsmListener) -> RatVerifierDriver<VC>,
        driverConfig: VC?
    ) {
        if (LOG.isDebugEnabled) {
            LOG.debug("Register '{}' driver to RAT prover registry", instance)
        }
        drivers[instance] = DriverWrapper(driverFactory, driverConfig)
    }

    /**
     * Unregister the driver from the registry
     */
    fun unregisterDriver(instance: String) {
        if (LOG.isDebugEnabled) {
            LOG.debug("Register '{}' driver from RAT prover registry", instance)
        }
        drivers.remove(instance)
    }

    /**
     * To start a Rat Verifier from the finite state machine
     *
     * First we check if the registry contains the RatVerifier instance, then we create a new
     * RatVerifierDriver from the driver wrapper that holds the corresponding
     * RatVerifierDriver class.
     *
     * The finite state machine is registered as the communication partner for the RatVerifier.
     * The RatVerifier will be initialized with a configuration, if present. Then it is started.
     */
    fun startRatVerifierDriver(mechanism: String?, listener: RatVerifierFsmListener): RatVerifierDriver<*>? {
        return drivers[mechanism]?.let { driverWrapper ->
            return try {
                driverWrapper.getInstance(listener).also { it.start() }
            } catch (e: Exception) {
                LOG.error("Error during RAT verifier start", e)
                null
            }
        }
    }
}
