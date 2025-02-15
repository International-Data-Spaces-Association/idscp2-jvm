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
package de.fhg.aisec.ids.idscp2.idscp_core.fsm

import com.google.protobuf.InvalidProtocolBufferException
import de.fhg.aisec.ids.idscp2.idscp_core.FastLatch
import de.fhg.aisec.ids.idscp2.idscp_core.api.configuration.AttestationConfig
import de.fhg.aisec.ids.idscp2.idscp_core.api.idscp_connection.Idscp2Connection
import de.fhg.aisec.ids.idscp2.idscp_core.drivers.DapsDriver
import de.fhg.aisec.ids.idscp2.idscp_core.drivers.RatProverDriver
import de.fhg.aisec.ids.idscp2.idscp_core.drivers.RatVerifierDriver
import de.fhg.aisec.ids.idscp2.idscp_core.error.Idscp2HandshakeException
import de.fhg.aisec.ids.idscp2.idscp_core.fsm.fsmListeners.RatProverFsmListener
import de.fhg.aisec.ids.idscp2.idscp_core.fsm.fsmListeners.RatVerifierFsmListener
import de.fhg.aisec.ids.idscp2.idscp_core.fsm.fsmListeners.ScFsmListener
import de.fhg.aisec.ids.idscp2.idscp_core.messages.Idscp2MessageHelper
import de.fhg.aisec.ids.idscp2.idscp_core.rat_registry.RatProverDriverRegistry
import de.fhg.aisec.ids.idscp2.idscp_core.rat_registry.RatVerifierDriverRegistry
import de.fhg.aisec.ids.idscp2.idscp_core.secure_channel.SecureChannel
import de.fhg.aisec.ids.idscp2.messages.IDSCP2.IdscpAck
import de.fhg.aisec.ids.idscp2.messages.IDSCP2.IdscpData
import de.fhg.aisec.ids.idscp2.messages.IDSCP2.IdscpMessage
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.HashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * The finite state machine FSM of the IDSCP2 protocol
 *
 *
 * Manages IDSCP2 Handshake, Re-Attestation, DAT-ReRequest and DAT-Re-Validation. Delivers
 * Internal Control Messages and Idscpv2Messages to the target receivers,
 * creates and manages the states and its transitions and implements security restriction to protect
 * the protocol against misuse and faulty, insecure or evil driver implementations.
 *
 * @author Leon Beckmann (leon.beckmann@aisec.fraunhofer.de)
 */
class FSM(
    connection: Idscp2Connection,
    secureChannel: SecureChannel,
    /**
     * Daps Driver instance
     */
    private val dapsDriver: DapsDriver,
    attestationConfig: AttestationConfig,
    ackTimeoutDelay: Long,
    handshakeTimeoutDelay: Long
) : RatProverFsmListener, RatVerifierFsmListener, ScFsmListener {
    /*  -----------   IDSCP2 Protocol States   ---------- */
    private val states = HashMap<FsmState, State>()

    enum class FsmState {
        STATE_CLOSED,
        STATE_WAIT_FOR_HELLO,
        STATE_WAIT_FOR_RAT,
        STATE_WAIT_FOR_RAT_VERIFIER,
        STATE_WAIT_FOR_RAT_PROVER,
        STATE_WAIT_FOR_DAT_AND_RAT_VERIFIER,
        STATE_WAIT_FOR_DAT_AND_RAT,
        STATE_ESTABLISHED,
        STATE_WAIT_FOR_ACK
    }

    /* FSM transition result */
    enum class FsmResultCode(val value: String) {
        UNKNOWN_TRANSITION("No transition available for given event in current state."),
        FSM_LOCKED("FSM is locked forever."),
        FSM_NOT_STARTED("Handshake was never started."),
        MISSING_DAT("DAT is missing."),
        INVALID_DAT("DAT is invalid."),
        IO_ERROR("Secure channel not available."),
        RAT_ERROR("RAT error occurred."),
        RAT_NEGOTIATION_ERROR("Error during negotiation of RAT mechanisms."),
        WOULD_BLOCK("Operation would block until FSM is in state 'ESTABLISHED'"),
        NOT_CONNECTED("Protocol is not in a connected state at the moment."),
        IDSCP_DATA_NOT_CACHED("IdscpData must be buffered in the 'WAIT_FOR_ACK' state."),
        OK("Action succeed.")
    }

    // return type for function, hold the return code and the next state of the transition
    data class FsmResult(val code: FsmResultCode, val nextState: State)

    private var currentState: State

    /*  ----------------   end of states   --------------- */
    private val connection: Idscp2Connection
    private val secureChannel: SecureChannel
    var ratProverDriver: RatProverDriver<*>? = null
        private set
    var ratVerifierDriver: RatVerifierDriver<*>? = null
        private set

    /**
     * RAT Driver Thread ID to identify the driver threads and check if messages are provided by
     * the current active driver or by any old driver, whose lifetime is already over
     *
     * Only one driver can be valid at a time
     */
    private var currentRatProverId: String? = // avoid messages from old prover drivers
        null
    private var currentRatVerifierId: String? = // avoid messages from old verifier drivers
        null

    /**
     * RAT Mechanisms, calculated during handshake in WAIT_FOR_HELLO_STATE
     */
    private lateinit var proverMechanism: String // RAT prover mechanism
    private lateinit var verifierMechanism: String // RAT Verifier mechanism

    /**
     * A FIFO-fair synchronization lock for the finite state machine
     */
    private val fsmIsBusy = ReentrantLock(true)

    /**
     * A condition to ensure no idscp messages can be provided by the secure channel to the fsm
     * before the handshake was started
     */
    private val onMessageBlock = fsmIsBusy.newCondition()

    /**
     * A condition for the idscpv2 handshake to wait until the handshake was successful and the
     * connection is established or the handshake failed and the fsm is locked forever
     */
    private val idscpHandshakeLock = fsmIsBusy.newCondition()
    private var handshakeResultAvailable = false
    private val idscpHandshakeCompletedLatch = FastLatch()

    /**
     * Check if FSM is closed forever
     */
    private var isLocked = false

    /**
     * Check if AckFlag is set
     */
    var ackFlag = false
        set(value) {
            field = value
            if (!value) {
                this.bufferedIdscpData = null
            }
        }
    private var bufferedIdscpData: IdscpMessage? = null

    /**
     * Alternating Bit
     */
    private var expectedAlternatingBit = AlternatingBit()
    private var nextSendAlternatingBit = AlternatingBit()

    /* ---------------------- Timers ---------------------- */
    private val datTimer: DynamicTimer
    private val ratTimer: StaticTimer
    private val handshakeTimer: StaticTimer
    private val proverHandshakeTimer: StaticTimer
    private val verifierHandshakeTimer: StaticTimer
    private val ackTimer: StaticTimer

    /**
     * Peer certificate
     */
    private var peerCertificate: X509Certificate? = null

    /**
     * Local peer dynamic attribute token
     */
    var localDat: ByteArray = "INVALID_DAT".toByteArray()

    /**
     * Remote peer dynamic attribute token
     */
    private var peerDat: ByteArray = "INVALID_DAT".toByteArray()

    fun setPeerDat(dat: ByteArray) {
        this.peerDat = dat
    }

    private fun checkForFsmCycles() {
        // check if current thread holds already the fsm lock, then we have a circle
        // this runs into an issue: onControlMessage must be called only from other threads!
        // if the current thread currently stuck within a fsm transition it will trigger another
        // transition on the old state and undefined behaviour occurred
        //
        // The IDSCP2 core and default driver will not run into this issue. It's a protection for
        // avoiding incorrect usage of the IDSCP2 library from further driver implementations
        //
        // Example:
        // Thread A stuck within a transition t1 that calls a function that calls
        // onControlMessage(InternalError). Then the error is handled in the current
        // state and the fsm will switch into state STATE_CLOSED and close all resources.
        //
        // Afterwards, the thread will continue the old transition t1 that might use some of the
        // closed resources and switch in a non-closed STATE, e.g. STATE_ESTABLISHED.
        // So our fsm would be broken and the behaviour is undefined and could leak security
        // vulnerabilities
        //
        if (fsmIsBusy.isHeldByCurrentThread) {
            val e = RuntimeException(
                "The current thread holds the fsm lock already. " +
                    "A circle might occur that could lead to undefined behaviour within the fsm"
            )
            // Log exception before throwing, since some threads swallow the exception without any notification
            LOG.error(e.message, e)
            throw e
        }
    }

    /**
     * Get a new IDSCP2 Message from the secure channel and provide it as an event to the fsm
     *
     * The checkForFsmCycles method first checks for risky thread cycles that occur by incorrect
     * driver implementations
     */
    override fun onMessage(data: ByteArray) {

        // check for incorrect usage
        checkForFsmCycles()

        // parse message and create new IDSCP Message event, then pass it to current state and
        // update new state
        val message: IdscpMessage = try {
            IdscpMessage.parseFrom(data)
        } catch (e: InvalidProtocolBufferException) {
            LOG.warn("Cannot parse raw data into IdscpMessage {}", data)
            return
        }
        val event = Event(message)
        // must wait when fsm is in state STATE_CLOSED --> wait() will be notified when fsm is
        // leaving STATE_CLOSED
        fsmIsBusy.lock()
        try {
            while (currentState == states[FsmState.STATE_CLOSED]) {
                if (isLocked) {
                    return
                }
                try {
                    onMessageBlock.await() // while waiting the fsmIsBusy lock is free for other threads
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            feedEvent(event)
        } finally {
            fsmIsBusy.unlock()
        }
    }

    /**
     * An internal control message (ICM) occurred, provide it to the fsm as an event
     */
    private fun onControlMessage(controlMessage: InternalControlMessage) {
        // create Internal Control Message Event and pass it to current state and update new state
        val e = Event(controlMessage)
        fsmIsBusy.lock()
        try {
            feedEvent(e)
        } finally {
            fsmIsBusy.unlock()
        }
    }

    override fun onRatProverMessage(controlMessage: InternalControlMessage, ratMessage: ByteArray) {
        processRatProverEvent(Event(controlMessage, Idscp2MessageHelper.createIdscpRatProverMessage(ratMessage)))
    }

    override fun onRatProverMessage(controlMessage: InternalControlMessage) {
        processRatProverEvent(Event(controlMessage))
    }

    /**
     * API for RatProver to provide Prover Messages to the fsm
     *
     * The checkForFsmCycles method first checks for risky thread cycles that occur by incorrect
     * driver implementations
     *
     * Afterwards the fsm lock is requested
     *
     * When the RatProverThread does not match the active prover tread id, the event will be
     * ignored, else the event is provided to the fsm
     */
    private fun processRatProverEvent(e: Event) {
        // check for incorrect usage
        checkForFsmCycles()

        fsmIsBusy.lock()
        try {
            if (Thread.currentThread().id.toString() == currentRatProverId) {
                feedEvent(e)
            } else {
                LOG.warn("An old or unknown Thread (${Thread.currentThread().id}) calls onRatProverMessage()")
            }
        } finally {
            fsmIsBusy.unlock()
        }
    }

    override fun onRatVerifierMessage(controlMessage: InternalControlMessage, ratMessage: ByteArray) {
        processRatVerifierEvent(Event(controlMessage, Idscp2MessageHelper.createIdscpRatVerifierMessage(ratMessage)))
    }

    override fun onRatVerifierMessage(controlMessage: InternalControlMessage) {
        processRatVerifierEvent(Event(controlMessage))
    }

    override val remotePeerDat: ByteArray
        get() = peerDat

    /**
     * API for RatVerifier to provide Verifier Messages to the fsm
     *
     * The checkForFsmCycles method first checks for risky thread cycles that occur by incorrect
     * driver implementations
     *
     * Afterwards the fsm lock is requested
     *
     * When the RatVerifierDriver does not match the active verifier thread id, the event will be
     * ignored, else the event is provided to the fsm
     */
    private fun processRatVerifierEvent(e: Event) {
        // check for incorrect usage
        checkForFsmCycles()

        fsmIsBusy.lock()
        try {
            if (Thread.currentThread().id.toString() == currentRatVerifierId) {
                feedEvent(e)
            } else {
                LOG.warn("An old or unknown Thread (${Thread.currentThread().id}) calls onRatVerifierMessage()")
            }
        } finally {
            fsmIsBusy.unlock()
        }
    }

    private fun onUpperEvent(event: Event): FsmResultCode {
        // check for incorrect usage
        checkForFsmCycles()
        fsmIsBusy.lock()
        try {
            return feedEvent(event)
        } finally {
            fsmIsBusy.unlock()
        }
    }

    /**
     * Feed the event to the current state and execute the runEntry method if the state has changed
     *
     * @return FsmResultCode, the result of the success of the triggered transition
     */
    private fun feedEvent(event: Event): FsmResultCode {
        val prevState = currentState
        val result = currentState.feedEvent(event)
        currentState = result.nextState
        if (prevState != currentState) {
            currentState.runEntryCode(this)
        }
        return result.code
    }

    /**
     * API to terminate the idscp connection by the user
     *
     * The checkForFsmCycles method first checks for risky thread cycles that occur by incorrect
     * driver implementations
     */
    fun closeConnection(): FsmResultCode {
        // check for incorrect usage
        if (LOG.isTraceEnabled) {
            LOG.trace("Sending stop message to connection peer...")
        }
        return onUpperEvent(Event(InternalControlMessage.IDSCP_STOP))
    }

    /**
     * API for the user to start the IDSCP2 handshake
     *
     * The checkForFsmCycles method first checks for risky thread cycles that occur by incorrect
     * driver implementations
     */
    @Throws(Idscp2HandshakeException::class)
    fun startIdscpHandshake() {
        // check for incorrect usage
        checkForFsmCycles()
        fsmIsBusy.lock()
        try {
            if (currentState == states[FsmState.STATE_CLOSED]) {
                if (isLocked) {
                    throw Idscp2HandshakeException("FSM is in a final closed state forever")
                }

                // trigger handshake init
                onControlMessage(InternalControlMessage.START_IDSCP_HANDSHAKE)

                // wait until handshake was successful or failed
                while (!handshakeResultAvailable) {
                    idscpHandshakeLock.await()
                }

                // check if not  connected and locked forever, then the handshake has failed
                if (!isConnected && isLocked) {
                    // handshake failed, throw exception
                    throw Idscp2HandshakeException("Handshake failed")
                }
            } else {
                throw Idscp2HandshakeException("Handshake has already been started")
            }
        } catch (e: InterruptedException) {
            throw Idscp2HandshakeException("Handshake failed because thread was interrupted")
        } finally {
            fsmIsBusy.unlock()
        }
    }

    /**
     * Send idscp data from the fsm via the secure channel to the peer
     */
    fun sendFromFSM(msg: IdscpMessage): Boolean {
        // send messages from fsm
        return try {
            secureChannel.send(msg.toByteArray())
        } catch (e: Exception) {
            // catch secure channel exception within an FSM transition to avoid transition cancellation
            LOG.error("Exception occurred during sending data via the secure channel: {}", e)
            false
        }
    }

    /**
     * Provide an Internal Control Message to the FSM
     *
     * The checkForFsmCycles method first checks for risky thread cycles that occur by incorrect
     * driver implementations
     */
    override fun onError(t: Throwable) {
        // Broadcast the error to the respective listeners when the fsm is not yet locked

        // run in async fire-and-forget coroutine to avoid cycles cause by protocol misuse
        if (!isFsmLocked) {
            GlobalScope.launch {
                connection.onError(t)
            }
        }

        // Check for incorrect usage
        checkForFsmCycles()
        onControlMessage(InternalControlMessage.ERROR)
    }

    /**
     * Provide an Internal Control Message to the FSM, caused by a secure channel closure
     *
     * The checkForFsmCycles method first checks for risky thread cycles that occur by incorrect
     * driver implementations
     */
    override fun onClose() {
        onUpperEvent(Event(InternalControlMessage.IDSCP_STOP))
    }

    /**
     * Set the peer Certificate from the secure channel and pass it to the DAPS driver
     */
    override fun setPeerX509Certificate(certificate: X509Certificate) {
        this.peerCertificate = certificate
    }

    override val remotePeerCertificate: X509Certificate?
        get() = this.peerCertificate

    /**
     * Send idscp message from the User via the secure channel
     */
    fun send(msg: ByteArray?): FsmResultCode {
        // Send messages from user only when idscp connection is established
        idscpHandshakeCompletedLatch.await()
        val idscpMessage = Idscp2MessageHelper.createIdscpDataMessage(msg)
        return onUpperEvent(Event(InternalControlMessage.SEND_DATA, idscpMessage))
    }

    /**
     * Repeat RAT Verification if remote peer, triggered by User
     */
    fun repeatRat(): FsmResultCode {
        // repeat rat only when idscp connection is established
        idscpHandshakeCompletedLatch.await()
        return onUpperEvent(Event(InternalControlMessage.REPEAT_RAT))
    }

    /**
     * Get local Dat
     */
    val getDynamicAttributeToken: ByteArray
        get() {
            return try {
                // get token from DAPS driver, update inner connection token and return
                val token = dapsDriver.token
                localDat = token
                token
            } catch (e: Exception) {
                LOG.error("Exception occurred during requesting DAT from DAPS:", e)
                "INVALID_DAT".toByteArray(StandardCharsets.UTF_8)
            }
        }

    /**
     * Check if FSM is in STATE ESTABLISHED
     */
    val isConnected: Boolean
        get() = currentState == states[FsmState.STATE_ESTABLISHED] ||
            currentState == states[FsmState.STATE_WAIT_FOR_ACK]

    /**
     * Notify handshake lock about result
     */
    fun notifyHandshakeCompleteLock() {
        fsmIsBusy.lock()
        try {
            handshakeResultAvailable = true
            idscpHandshakeLock.signal()
            idscpHandshakeCompletedLatch.unlock()
        } finally {
            fsmIsBusy.unlock()
        }
    }

    /**
     * Calculate the RatProver mechanism
     *
     * The remote peer decides about its verifier mechanism, so we have to prefer remoteExpected list
     *
     * @return The String of the cipher or null if no match was found
     */
    fun getRatProverMechanism(localSupportedProver: Array<String>, remoteExpectedVerifier: Array<String>): String? {
        if (localSupportedProver.isEmpty()) {
            LOG.warn("Got empty RAT localSupportedProver suite")
            return null
        }

        if (remoteExpectedVerifier.isEmpty()) {
            LOG.warn("Got empty RAT remoteExpectedVerifier suite")
            return null
        }

        if (LOG.isTraceEnabled) {
            LOG.trace(
                "Calculate RAT prover mechanism for given local provers: {} " +
                    "and remote verifiers: {}",
                localSupportedProver.contentToString(),
                remoteExpectedVerifier.contentToString()
            )
        }

        val match = matchRatMechanisms(remoteExpectedVerifier, localSupportedProver)
        if (LOG.isDebugEnabled) {
            LOG.debug("RAT prover mechanism: '{}'", match)
        }
        return match
    }

    /**
     * Calculate the RatVerifier mechanism
     *
     * We have to decide our verifier mechanism, so we have to prefer localExpected list
     *
     * @return The String of the cipher or null if no match was found
     */
    fun getRatVerifierMechanism(localExpectedVerifier: Array<String>, remoteSupportedProver: Array<String>): String? {
        if (localExpectedVerifier.isEmpty()) {
            LOG.warn("Got empty RAT localExpectedVerifier suite")
            return null
        }

        if (remoteSupportedProver.isEmpty()) {
            LOG.warn("Got empty RAT remoteSupportedProver suite")
            return null
        }

        if (LOG.isTraceEnabled) {
            LOG.trace(
                "Calculate RAT verifier mechanism for given local verifiers: {} " +
                    "and remote provers: {}",
                localExpectedVerifier.contentToString(),
                remoteSupportedProver.contentToString()
            )
        }

        val match = matchRatMechanisms(localExpectedVerifier, remoteSupportedProver)
        if (LOG.isDebugEnabled) {
            LOG.debug("RAT verifier mechanism: '{}'", match)
        }
        return match
    }

    private fun matchRatMechanisms(primary: Array<String>, secondary: Array<String>): String? {
        for (p in primary) {
            for (s in secondary) {
                if (p == s) {
                    return p
                }
            }
        }
        // no match
        return null
    }

    /**
     * Stop current RatVerifier if active and start the RatVerifier from the
     * RatVerifierDriver Registry that matches the verifier mechanism
     *
     * @return false if no match was found
     */
    fun restartRatVerifierDriver(): Boolean {
        // assume verifier mechanism is set
        stopRatVerifierDriver()
        ratVerifierDriver = RatVerifierDriverRegistry.startRatVerifierDriver(verifierMechanism, this)
        return ratVerifierDriver?.let {
            // safe the thread ID
            currentRatVerifierId = it.id.toString()
            if (LOG.isTraceEnabled) {
                LOG.trace("Start verifier_handshake timeout")
            }
            verifierHandshakeTimer.resetTimeout()
            true
        } ?: run {
            LOG.error("Cannot create instance of RAT_VERIFIER_DRIVER")
            currentRatVerifierId = ""
            false
        }
    }

    /**
     * Terminate the RatVerifierDriver
     */
    fun stopRatVerifierDriver() {
        verifierHandshakeTimer.cancelTimeout()
        ratVerifierDriver?.let {
            if (it.isAlive) {
                it.interrupt()

                // run in async fire-and-forget coroutine to avoid cycles caused by protocol misuse
                GlobalScope.launch {
                    it.terminate()
                }
            }
        }
    }

    /**
     * Stop current RatProver if active and start the RatProver from the
     * RatProverDriver Registry that matches the prover mechanism
     *
     * @return false if no match was found
     */
    fun restartRatProverDriver(): Boolean {
        // assume prover mechanism is set
        stopRatProverDriver()
        ratProverDriver = RatProverDriverRegistry.startRatProverDriver(proverMechanism, this)
        return ratProverDriver?.let {
            // Save the thread ID
            currentRatProverId = it.id.toString()
            if (LOG.isTraceEnabled) {
                LOG.trace("Start prover_handshake timeout")
            }
            proverHandshakeTimer.resetTimeout()
            true
        } ?: run {
            LOG.error("Cannot create instance of RAT_PROVER_DRIVER")
            currentRatProverId = ""
            false
        }
    }

    /**
     * Terminate the RatProverDriver
     */
    private fun stopRatProverDriver() {
        proverHandshakeTimer.cancelTimeout()
        ratProverDriver?.let {
            if (it.isAlive) {
                it.interrupt()

                // Run in async fire-and-forget coroutine to avoid cycles caused by protocol misuse
                GlobalScope.launch {
                    it.terminate()
                }
            }
        }
    }

    /**
     * Lock the fsm forever, terminate the timers and drivers, close the secure channel
     * and notify handshake lock if necessary
     */
    fun shutdownFsm() {

        if (LOG.isTraceEnabled) {
            LOG.trace("Shutting down FSM of connection {}...", connection.id)
            LOG.trace("Running close handlers of connection {}...", connection.id)
        }

        if (LOG.isTraceEnabled) {
            LOG.trace("Mark FSM as terminated...")
        }
        isLocked = true

        // run in async fire-and-forget coroutine to avoid cycles caused by protocol misuse
        GlobalScope.launch {
            connection.onClose()
        }

        // run in async fire-and-forget coroutine to avoid cycles caused by protocol misuse
        GlobalScope.launch {
            if (LOG.isTraceEnabled) {
                LOG.trace("Closing secure channel of connection {}...", connection.id)
            }
            secureChannel.close()
        }

        if (LOG.isTraceEnabled) {
            LOG.trace("Clearing timeouts...")
        }
        datTimer.cancelTimeout()
        ratTimer.cancelTimeout()
        handshakeTimer.cancelTimeout()
        ackTimer.cancelTimeout()
        if (LOG.isTraceEnabled) {
            LOG.trace("Stopping RAT components...")
        }
        // Cancels proverHandshakeTimer
        stopRatProverDriver()
        // Cancels verifierHandshakeTimer
        stopRatVerifierDriver()

        // Notify upper layer via handshake or closeListener
        if (!handshakeResultAvailable) {
            if (LOG.isTraceEnabled) {
                LOG.trace("Notify handshake lock...")
            }
            notifyHandshakeCompleteLock()
        }
    }

    /**
     * Provide IDSCP2 message to the message listener
     */
    private fun notifyIdscpMsgListener(data: ByteArray) {
        // run in async fire-and-forget coroutine to avoid cycles caused by protocol misuse
        GlobalScope.launch {
            connection.onMessage(data)

            if (LOG.isTraceEnabled) {
                LOG.trace("Idscp data has been passed to connection listener")
            }
        }
    }

    val isFsmLocked: Boolean
        get() = isLocked

    fun getState(state: FsmState): State {
        return states[state] ?: throw NoSuchElementException("State unknown")
    }

    val isNotClosed: Boolean
        get() = currentState == getState(FsmState.STATE_CLOSED)

    fun setRatMechanisms(proverMechanism: String, verifierMechanism: String) {
        this.proverMechanism = proverMechanism
        this.verifierMechanism = verifierMechanism
    }

    val getBufferedIdscpMessage: IdscpMessage?
        get() = bufferedIdscpData

    /**
     * Handle IdscpAck
     * @return true if everything if ack flag was cleared correctly, else false
     */
    fun recvAck(ack: IdscpAck): Boolean {
        if (ackFlag) {
            if (LOG.isTraceEnabled) {
                LOG.trace("Received IdscpAck with alternating bit {}, cancel flag in fsm", ack.alternatingBit)
            }
            if (nextSendAlternatingBit.asBoolean() != ack.alternatingBit) {
                if (LOG.isTraceEnabled) {
                    LOG.trace("Received IdscpAck with wrong alternating bit. Ignoring")
                }
            } else {
                ackFlag = false
                ackTimer.cancelTimeout()
                if (LOG.isTraceEnabled) {
                    LOG.trace("Alternate nextSend bit from {}", nextSendAlternatingBit.asBoolean())
                }
                nextSendAlternatingBit.alternate()
                return true
            }
        } else {
            LOG.warn("Received unexpected IdscpAck")
        }
        return false
    }

    fun recvData(data: IdscpData) {
        if (LOG.isTraceEnabled) {
            LOG.trace("Received IdscpData with alternating bit {}", data.alternatingBit)
        }

        if (data.alternatingBit != expectedAlternatingBit.asBoolean()) {
            if (LOG.isTraceEnabled) {
                LOG.trace("Received IdscpData with unexpected alternating bit. Could be an old packet replayed. Ignore it.")
            }
        } else {
            if (LOG.isTraceEnabled) {
                LOG.trace("Send IdscpAck with received alternating bit {}", data.alternatingBit)
            }
            if (!sendFromFSM(Idscp2MessageHelper.createIdscpAckMessage(data.alternatingBit))) {
                LOG.error("Cannot send ACK")
            }
            if (LOG.isTraceEnabled) {
                LOG.trace("Alternate expected bit from {}", expectedAlternatingBit.asBoolean())
            }
            expectedAlternatingBit.alternate()

            // forward payload data to upper layer
            notifyIdscpMsgListener(data.data.toByteArray())
        }
    }

    fun setBufferedIdscpData(msg: IdscpMessage) {
        this.bufferedIdscpData = msg
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(FSM::class.java)
    }

    init {
        val handshakeTimeoutHandler = Runnable {
            if (LOG.isTraceEnabled) {
                LOG.trace("HANDSHAKE_TIMER_EXPIRED")
            }
            onControlMessage(InternalControlMessage.TIMEOUT)
        }
        val datTimeoutHandler = Runnable {
            if (LOG.isTraceEnabled) {
                LOG.trace("DAT_TIMER_EXPIRED")
            }
            onControlMessage(InternalControlMessage.DAT_TIMER_EXPIRED)
        }
        val ratTimeoutHandler = Runnable {
            if (LOG.isTraceEnabled) {
                LOG.trace("RAT_TIMER_EXPIRED")
            }
            onControlMessage(InternalControlMessage.REPEAT_RAT)
        }
        val proverTimeoutHandler = Runnable {
            if (LOG.isTraceEnabled) {
                LOG.trace("RAT_PROVER_HANDSHAKE_TIMER_EXPIRED")
            }
            onControlMessage(InternalControlMessage.TIMEOUT)
        }
        val verifierTimeoutHandler = Runnable {
            if (LOG.isTraceEnabled) {
                LOG.trace("RAT_VERIFIER_HANDSHAKE_TIMER_EXPIRED")
            }
            onControlMessage(InternalControlMessage.TIMEOUT)
        }
        val ackTimeoutHandler = Runnable {
            if (LOG.isTraceEnabled) {
                LOG.trace("ACK_TIMER_EXPIRED")
            }
            onControlMessage(InternalControlMessage.ACK_TIMER_EXPIRED)
        }

        datTimer = DynamicTimer(fsmIsBusy, datTimeoutHandler)
        handshakeTimer = StaticTimer(fsmIsBusy, handshakeTimeoutHandler, handshakeTimeoutDelay)
        proverHandshakeTimer = StaticTimer(fsmIsBusy, proverTimeoutHandler, handshakeTimeoutDelay)
        verifierHandshakeTimer = StaticTimer(fsmIsBusy, verifierTimeoutHandler, handshakeTimeoutDelay)
        ratTimer = StaticTimer(fsmIsBusy, ratTimeoutHandler, attestationConfig.ratTimeoutDelay)
        ackTimer = StaticTimer(fsmIsBusy, ackTimeoutHandler, ackTimeoutDelay)

        /* ------------- FSM STATE Initialization -------------*/
        states[FsmState.STATE_CLOSED] = StateClosed(
            this, onMessageBlock, attestationConfig
        )
        states[FsmState.STATE_WAIT_FOR_HELLO] = StateWaitForHello(
            this, handshakeTimer, datTimer, dapsDriver, attestationConfig
        )
        states[FsmState.STATE_WAIT_FOR_RAT] = StateWaitForRat(
            this, handshakeTimer, verifierHandshakeTimer, proverHandshakeTimer, ratTimer
        )
        states[FsmState.STATE_WAIT_FOR_RAT_PROVER] = StateWaitForRatProver(
            this, ratTimer, handshakeTimer, proverHandshakeTimer, ackTimer
        )
        states[FsmState.STATE_WAIT_FOR_RAT_VERIFIER] = StateWaitForRatVerifier(
            this, ratTimer, handshakeTimer, verifierHandshakeTimer, ackTimer
        )
        states[FsmState.STATE_WAIT_FOR_DAT_AND_RAT] = StateWaitForDatAndRat(
            this, handshakeTimer, proverHandshakeTimer, datTimer, dapsDriver
        )
        states[FsmState.STATE_WAIT_FOR_DAT_AND_RAT_VERIFIER] = StateWaitForDatAndRatVerifier(
            this, handshakeTimer, datTimer, dapsDriver
        )
        states[FsmState.STATE_ESTABLISHED] = StateEstablished(
            this, ratTimer, handshakeTimer, ackTimer, nextSendAlternatingBit
        )
        states[FsmState.STATE_WAIT_FOR_ACK] = StateWaitForAck(
            this, ratTimer, handshakeTimer, ackTimer
        )

        // Set initial state
        currentState = states[FsmState.STATE_CLOSED] ?: throw NoSuchElementException("State unknown")
        this.connection = connection
        this.secureChannel = secureChannel
    }
}
