/*
 * Copyright © 2017-2023  Kynetics  LLC
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.hara.ddiclient.api.actors

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import org.eclipse.hara.ddi.api.DdiClient
import org.eclipse.hara.ddi.api.model.*
import org.eclipse.hara.ddiclient.api.MessageListener
import org.eclipse.hara.ddiclient.api.actors.ConnectionManager.Companion.Message.In
import org.eclipse.hara.ddiclient.api.actors.ConnectionManager.Companion.Message.Out
import org.eclipse.hara.ddiclient.api.actors.ConnectionManager.Companion.Message.Out.Err.ErrMsg
import org.joda.time.*
import retrofit2.Response
import java.util.*
import kotlin.concurrent.timer
import kotlin.math.pow

@OptIn(ObsoleteCoroutinesApi::class)
class ConnectionManager
private constructor(scope: ActorScope) : AbstractActor(scope) {

    private val client: DdiClient = coroutineContext[HaraClientContext]!!.ddiClient
    private val notificationManager = coroutineContext[NMActor]!!.ref
    private val configDataProvider = coroutineContext[HaraClientContext]!!.configDataProvider
    private val feedbackChannel: Channel<Feedback> = Channel(UNLIMITED)

    override fun beforeCloseChannel() {
        feedbackChannel.close()
        super.beforeCloseChannel()
    }

    init{
        CoroutineScope(Dispatchers.IO).launch{

            val shouldRetry: (Response<Unit>) -> Boolean = { response ->
                when(response.code()){
                    in 500 .. 599, 409, 429 -> true
                    else -> false
                }
            }

            for(msg in feedbackChannel){
                var attempt = 0;
                do{
                    val retry = sendFeedback(msg.decorateMessage(attempt))
                        .map(shouldRetry)
                        .getOrDefault(true)
                        .run { this && ++attempt < 168 }

                    if(retry){
                        delay(2.0.pow(attempt).toLong().coerceIn(60_000, 3_600_000))
                    }
                } while(retry)
                if(msg.closeAction){
                    channel.send(In.Ping)
                }
            }
        }
    }

    private suspend fun sendFeedback(msg: Feedback): Result<Response<Unit>>  = runCatching {
        when (msg) {
            is In.DeploymentFeedback -> client.postDeploymentActionFeedback(msg.feedback.id, msg.feedback)
            is In.CancelFeedback -> client.postCancelActionFeedback(msg.feedback.id, msg.feedback)
        }
    }

    private fun Feedback.decorateMessage(attempt:Int):Feedback{
        val detailsDecorator: (List<String>, String) -> List<String> = { list, time ->
            list.toMutableList().apply {
                if(attempt > 0){
                    add("Re-sent at ${Instant.now()} (First try at $time")
                }
            }
        }

        return when(this){
            is In.DeploymentFeedback -> copy(feedback = feedback.copy(
                time = LocalDateTime(Instant.now(), DateTimeZone.UTC).toString(),
                status = feedback.status.copy(details = detailsDecorator(feedback.status.details, feedback.time))
            ))
            is In.CancelFeedback -> copy(feedback = feedback.copy(
                time = LocalDateTime(Instant.now(), DateTimeZone.UTC).toString(),
                status = feedback.status.copy(details = detailsDecorator(feedback.status.details, feedback.time))
            ))
        }
    }

    private fun stoppedReceive(state: State): Receive = { msg ->
        when (msg) {

            is In.Start -> become(runningReceive(startPing(state)))

            is In.Stop -> {}

            is In.Register -> become(stoppedReceive(state.withReceiver(msg.listener)))

            is In.Unregister -> become(stoppedReceive(state.withoutReceiver(msg.listener)))

            is In.SetPing -> become(stoppedReceive(state.copy(clientPingInterval = msg.duration, lastPing = Instant.EPOCH)))

            else -> unhandled(msg)
        }
    }

    private fun runningReceive(state: State): Receive = { msg ->
        when (msg) {

            is In.Start -> {}

            is In.Stop -> become(stoppedReceive(stopPing(state)))

            is In.Register -> become(runningReceive(state.withReceiver(msg.listener)))

            is In.Unregister -> become(runningReceive(state.withoutReceiver(msg.listener)))

            is In.SetPing -> become(runningReceive(startPing(state.copy(clientPingInterval = msg.duration, lastPing = Instant.EPOCH))))

            is In.ForcePing -> {
                become(runningReceive(state.clearEtags()))
                channel.send(In.SetPing(null))
            }

            is In.Ping -> onPing(state)

            is Feedback -> {
                feedbackChannel.send(msg)
            }

            is In.ConfigDataFeedback -> {
                exceptionHandler(state) {
                    client.putConfigData(msg.cfgDataReq) {
                        configDataProvider.onConfigDataUpdate()
                    }
                }
            }

            else -> {
                unhandled(msg)
            }
        }
    }

    private suspend fun onControllerBaseChange(state: State, s: State, res: ControllerBaseResponse, newControllerBaseEtag: String) {
        if (res.requireConfigData()) {
            this.send(Out.ConfigDataRequired, state)
        }

        var actionFound = false
        var etag = state.deploymentEtag
        if (res.requireDeployment()) {
            notificationManager.send(MessageListener.Message.Event.UpdateAvailable(res.deploymentActionId()))
            client.onDeploymentActionDetailsChange(res.deploymentActionId(), 0, state.deploymentEtag) { deplBaseResp, newDeploymentEtag ->
                etag = newDeploymentEtag
                this.send(Out.DeploymentInfo(deplBaseResp, state.deploymentEtag.isEmpty()), state)
            }
            actionFound = true
        }

        if (res.requireCancel()) {
            val res2 = client.getCancelActionDetails(res.cancelActionId())
            this.send(Out.DeploymentCancelInfo(res2), state)
            actionFound = true
        }

        if (!actionFound) {
            this.send(Out.NoAction, state)
        }

        val newState = s.copy(controllerBaseEtag = newControllerBaseEtag, deploymentEtag = etag)
                .withServerSleep(res.config.polling.sleep)
                .withoutBackoff()
        become(runningReceive(startPing(newState)))
    }

    private suspend fun onPing(state: State) {
        LOG.info("Execute ping calls to the server...")
        val s = state.copy(lastPing = Instant.now())
        runCatching {

            notificationManager.send(MessageListener.Message.Event.Polling)

            if (!configDataProvider.isUpdated()) {
                this.send(Out.ConfigDataRequired, state)
            }

            client.onControllerActionsChange(state.controllerBaseEtag) { res, newEtag ->
                onControllerBaseChange(state, s, res, newEtag)
            }
        }.onFailure { t ->
            fun loopMsg(t: Throwable): String = t.message + if (t.cause != null) " ${loopMsg(t.cause!!)}" else ""
            val errorDetails = "exception: ${t.javaClass} message: ${loopMsg(t)}"
            this.send(ErrMsg(errorDetails), state)
            LOG.warn(t.message, t)
            become(runningReceive(startPing(s.nextBackoff().clearEtags())))
            notificationManager.send(MessageListener.Message.Event.Error(listOf(errorDetails)))
        }
    }

    private suspend fun exceptionHandler(state: State, function: suspend () -> Unit) {
        runCatching {
            function.invoke()
        }.onFailure { t ->
            this.send(ErrMsg("exception: ${t.javaClass}" + if (t.message != null) " message: ${t.message}" else ""), state)
            LOG.warn(t.message, t)
        }
    }

    private fun startPing(state: State): State {
        val now = Instant.now()
        val elapsed = Duration(state.lastPing, now)
        val timer = timer(name = "Polling",
                daemon = true,
                initialDelay = Math.max(state.pingInterval.minus(elapsed).millis, 0),
                period = Math.max(state.pingInterval.millis, 5_000)) {
            launch {
                channel.send(In.Ping)
            }
        }
        return stopPing(state).copy(timer = timer)
    }

    private fun stopPing(state: State): State = if (state.timer != null) {
        state.timer.cancel()
        state.copy(timer = null)
    } else {
        state
    }

    private suspend fun send(msg: Out, state: State) {
        state.receivers.forEach { it.send(msg) }
    }

    init {
        become(stoppedReceive(State()))
    }

    companion object {
        fun of(scope: ActorScope) = ConnectionManager(scope)

        private data class State(
            val serverPingInterval: Duration = Duration.standardSeconds(0),
            val clientPingInterval: Duration? = null,
            val backoffPingInterval: Duration? = null,
            val lastPing: Instant? = Instant.EPOCH,
            val deploymentEtag: String = "",
            val controllerBaseEtag: String = "",
            val timer: Timer? = null,
            val receivers: Set<ActorRef> = emptySet()
        ) {
            val pingInterval = when {
                backoffPingInterval != null -> backoffPingInterval
                clientPingInterval != null -> clientPingInterval
                else -> serverPingInterval
            }
            fun nextBackoff() = if (backoffPingInterval == null)
                this.copy(backoffPingInterval = Duration.standardSeconds(1))
            else this.copy(backoffPingInterval = minOf(backoffPingInterval.multipliedBy(2), Duration.standardMinutes(1)))

            fun clearEtags():State = copy(controllerBaseEtag = "", deploymentEtag = "")

            fun withoutBackoff() = if (backoffPingInterval != null) this.copy(backoffPingInterval = null) else this

            fun withServerSleep(sleep: String): State {
                fun sleepStr2duration(str: String): Duration {
                    val fields = str.split(':').map { Integer.parseInt(it).toLong() }.toTypedArray()
                    return Duration.standardHours(fields[0]).plus(
                            Duration.standardMinutes(fields[1])).plus(
                            Duration.standardSeconds(fields[2]))
                }
                val newServerPingInterval = sleepStr2duration(sleep)
                return if (newServerPingInterval != serverPingInterval) this.copy(serverPingInterval = newServerPingInterval)
                else this
            }
            fun withReceiver(receiver: ActorRef) = this.copy(receivers = receivers + receiver)

            fun withoutReceiver(receiver: ActorRef) = this.copy(receivers = receivers - receiver)
        }



        sealed interface Feedback{
            val closeAction:Boolean
        }
        sealed class Message {

            sealed class In : Message() {
                object Start : In()
                object Stop : In()
                object Ping : In()
                object ForcePing : In()
                data class Register(val listener: ActorRef) : In()
                data class Unregister(val listener: ActorRef) : In()
                data class SetPing(val duration: Duration?) : In()
                data class DeploymentFeedback(val feedback: DeploymentFeedbackRequest):In(), Feedback{
                    override val closeAction: Boolean = feedback.status.execution == DeploymentFeedbackRequest.Status.Execution.closed
                }
                data class CancelFeedback(val feedback: CancelFeedbackRequest):In(), Feedback{
                    override val closeAction: Boolean = feedback.status.execution == CancelFeedbackRequest.Status.Execution.closed
                }
                data class ConfigDataFeedback(val cfgDataReq: ConfigurationDataRequest):In()
            }

            open class Out : Message() {
                object ConfigDataRequired : Out()
                data class DeploymentInfo(val info: DeploymentBaseResponse, val forceAuthRequest:Boolean  = false) : Out()
                data class DeploymentCancelInfo(val info: CancelActionResponse) : Out()

                object NoAction : Out()

                sealed class Err : Out() {
                    data class ErrMsg(val message: String) : Err()
                }
            }
        }
    }
}
