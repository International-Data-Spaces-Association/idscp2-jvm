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

import de.fhg.aisec.ids.idscp2.idscp_core.fsm.FSM.FsmState
import de.fhg.aisec.ids.idscp2.idscp_core.messages.Idscp2MessageHelper
import de.fhg.aisec.ids.idscp2.messages.IDSCP2.IdscpClose.CloseCause
import de.fhg.aisec.ids.idscp2.messages.IDSCP2.IdscpMessage
import org.slf4j.LoggerFactory

/**
 * The Established State of the FSM of the IDSCP2 protocol.
 * Allows message exchange over the IDSCP2 protocol between two connectors
 *
 * @author Leon Beckmann (leon.beckmann@aisec.fraunhofer.de)
 */
class StateEstablished(
    fsm: FSM,
    ratTimer: StaticTimer,
    handshakeTimer: StaticTimer,
    ackTimer: StaticTimer,
    alternatingBit: AlternatingBit
) : State() {

    override fun runEntryCode(fsm: FSM) {
        if (LOG.isTraceEnabled) {
            LOG.trace("Switched to state STATE_ESTABLISHED")
        }
        fsm.notifyHandshakeCompleteLock()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(StateEstablished::class.java)
    }

    init {

        /*---------------------------------------------------
         * STATE_ESTABLISHED - Transition Description
         * ---------------------------------------------------
         * onICM: error ---> {timeouts.cancel(), send IDSCP_CLOSE} ---> STATE_CLOSED
         * onICM: stop ---> {timeouts.cancel()} ---> STATE_CLOSED
         * onICM: re_rat ---> {send IDSCP_RE_RAT, start RAT_VERIFIER} ---> STATE_WAIT_FOR_RAT_VERIFIER
         * onICM: send_data ---> {send IDS_DATA} ---> STATE_WAIT_FOR_ACK / STATE_CLOSED
         * onICM: dat_timeout ---> {send IDSCP_DAT_EXPIRED} ---> STATE_WAIT_FOR_DAT_AND_RAT_VERIFIER
         * onMessage: IDSCP_DATA ---> {delegate to connection} ---> STATE_ESTABLISHED
         * onMessage: IDSCP_RERAT ---> {start RAT_PROVER} ---> STATE_WAIT_FOR_RAT_PROVER
         * onMessage: IDSCP_DAT_EXPIRED ---> {send IDSCP_DAT, start RAT_PROVER} ---> STATE_WAIT_FOR_RAT_PROVER
         * onMessage: IDSCP_CLOSE ---> {timeouts.cancel()} ---> STATE_CLOSED
         * ALL_OTHER_MESSAGES ---> {} ---> STATE_ESTABLISHED
         * --------------------------------------------------- */
        addTransition(
            InternalControlMessage.ERROR.value,
            Transition {
                LOG.warn("Error occurred, close idscp connection")
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_CLOSED))
            }
        )

        addTransition(
            InternalControlMessage.IDSCP_STOP.value,
            Transition {
                if (LOG.isTraceEnabled) {
                    LOG.trace("Send IDSCP_CLOSE")
                }
                fsm.sendFromFSM(
                    Idscp2MessageHelper.createIdscpCloseMessage(
                        "User close",
                        CloseCause.USER_SHUTDOWN
                    )
                )
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_CLOSED))
            }
        )

        addTransition(
            InternalControlMessage.SEND_DATA.value,
            Transition {
                // repack data, include alternating bit
                val idscpMessage = Idscp2MessageHelper.createIdscpDataMessageWithAltBit(
                    it.idscpMessage.idscpData.data.toByteArray(), alternatingBit
                )

                // send repacked data
                if (LOG.isTraceEnabled) {
                    LOG.trace("Send IdscpData")
                }
                if (fsm.sendFromFSM(idscpMessage)) {
                    // Set Ack Flag
                    fsm.ackFlag = true
                    fsm.setBufferedIdscpData(idscpMessage)
                    ackTimer.start()
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_WAIT_FOR_ACK))
                } else {
                    LOG.warn("Cannot send IdscpData, shutdown FSM")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.IO_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }
            }
        )
        addTransition(
            InternalControlMessage.REPEAT_RAT.value,
            Transition {
                if (LOG.isDebugEnabled) {
                    LOG.debug("Re-Attestation triggered")
                }
                if (LOG.isTraceEnabled) {
                    LOG.trace("Send IDSCP_RERAT, start RAT_VERIFIER")
                }
                ratTimer.cancelTimeout()
                if (!fsm.sendFromFSM(Idscp2MessageHelper.createIdscpReRatMessage(""))) {
                    LOG.warn("Cannot send ReRat message")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.IO_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }
                if (!fsm.restartRatVerifierDriver()) {
                    LOG.warn("Cannot run Rat verifier, close idscp connection")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.RAT_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_WAIT_FOR_RAT_VERIFIER))
            }
        )
        addTransition(
            InternalControlMessage.DAT_TIMER_EXPIRED.value,
            Transition {
                ratTimer.cancelTimeout()
                if (LOG.isDebugEnabled) {
                    LOG.debug("DAT expired, request new DAT from peer and trigger a re-attestation")
                }
                if (LOG.isTraceEnabled) {
                    LOG.trace("Send IDSCP_DAT_EXPIRED")
                }
                if (!fsm.sendFromFSM(Idscp2MessageHelper.createIdscpDatExpiredMessage())) {
                    LOG.warn("Cannot send DatExpired message")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.IO_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }
                if (LOG.isTraceEnabled) {
                    LOG.trace("Set handshake timeout")
                }
                handshakeTimer.resetTimeout()
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_WAIT_FOR_DAT_AND_RAT_VERIFIER))
            }
        )
        addTransition(
            IdscpMessage.IDSCPRERAT_FIELD_NUMBER,
            Transition {
                if (LOG.isDebugEnabled) {
                    LOG.debug("Peer is requesting a re-attestation")
                }
                if (!fsm.restartRatProverDriver()) {
                    LOG.warn("Cannot run Rat prover, close idscp connection")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.RAT_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_WAIT_FOR_RAT_PROVER))
            }
        )
        addTransition(
            IdscpMessage.IDSCPDATEXPIRED_FIELD_NUMBER,
            Transition {
                if (LOG.isDebugEnabled) {
                    LOG.debug("Peer is requesting a new DAT, followed by a re-attestation")
                }
                if (!fsm.sendFromFSM(Idscp2MessageHelper.createIdscpDatMessage(fsm.getDynamicAttributeToken))) {
                    LOG.warn("Cannot send Dat message")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.IO_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }
                if (!fsm.restartRatProverDriver()) {
                    LOG.warn("Cannot run Rat prover, close idscp connection")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.RAT_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_WAIT_FOR_RAT_PROVER))
            }
        )

        addTransition(
            IdscpMessage.IDSCPDATA_FIELD_NUMBER,
            Transition {
                fsm.recvData(it.idscpMessage.idscpData)
                FSM.FsmResult(FSM.FsmResultCode.OK, this)
            }
        )

        addTransition(
            IdscpMessage.IDSCPCLOSE_FIELD_NUMBER,
            Transition {
                if (LOG.isTraceEnabled) {
                    LOG.trace("Receive IDSCP_CLOSED")
                }
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_CLOSED))
            }
        )

        setNoTransitionHandler {
            if (LOG.isTraceEnabled) {
                LOG.trace("No transition available for given event $it, stay in state STATE_ESTABLISHED")
            }
            FSM.FsmResult(FSM.FsmResultCode.UNKNOWN_TRANSITION, this)
        }
    }
}
