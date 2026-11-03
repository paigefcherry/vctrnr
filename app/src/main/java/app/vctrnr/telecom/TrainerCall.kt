package app.vctrnr.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * Registers an earbud-mic session with Android as a (self-managed, never actually dialled)
 * VoIP call.
 *
 * Why: in call mode the Buds' gestures become *call* controls (double tap = hang up), sent
 * over the Bluetooth hands-free channel. Android only hands those to apps whose audio is a
 * real call in its Telecom system. So each session is one "call": hanging up from the
 * earbuds ends the call, which stops the session.
 */
object TrainerCall {
    enum class State { Idle, Connecting, Active, Failed }

    @Volatile var state: State = State.Idle
        private set

    /** Main thread: the call was ended from outside (earbud hang-up, an incoming phone call…). */
    @Volatile var onEndedRemotely: (() -> Unit)? = null

    /** True from asking for the call until it ends. While true, Telecom owns the audio. */
    val inCall: Boolean get() = state == State.Connecting || state == State.Active

    @Volatile private var connection: TrainerConnection? = null
    private val main = Handler(Looper.getMainLooper())

    /** Asks Telecom for the call. False straight away if it can't be placed right now. */
    fun start(context: Context): Boolean {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val handle = PhoneAccountHandle(ComponentName(context, TrainerConnectionService::class.java), ACCOUNT_ID)
        return try {
            telecom.registerPhoneAccount(
                PhoneAccount.builder(handle, "vctrnr")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP))
                    .build(),
            )
            if (!telecom.isOutgoingCallPermitted(handle)) {
                state = State.Failed
                return false
            }
            state = State.Connecting
            telecom.placeCall(
                Uri.fromParts(PhoneAccount.SCHEME_SIP, "vctrnr", null),
                Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle) },
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the Telecom call", e)
            state = State.Failed
            false
        }
    }

    /** Ask Telecom to put the call audio on the earbuds (it usually does by itself). */
    fun routeToBluetooth() {
        val c = connection ?: return
        main.post {
            @Suppress("DEPRECATION") // Replacement (requestCallEndpointChange) is API 34+.
            c.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
        }
    }

    /** Ends our call, if any. Not reported as a remote hang-up. */
    fun end() {
        val c = connection
        connection = null
        state = State.Idle
        if (c != null) main.post { c.finish() }
    }

    internal fun onCreated(c: TrainerConnection) {
        connection = c
        state = State.Active
    }

    internal fun onFailed() {
        state = State.Failed
    }

    internal fun onEndedByOthers(c: TrainerConnection) {
        if (connection !== c) return
        connection = null
        state = State.Idle
        main.post { onEndedRemotely?.invoke() }
    }

    private const val TAG = "vctrnr.Call"
    private const val ACCOUNT_ID = "vctrnr"
}

/** Telecom binds this while a session "call" exists. */
class TrainerConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        val c = TrainerConnection()
        c.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        c.setCallerDisplayName("vctrnr", TelecomManager.PRESENTATION_ALLOWED)
        c.setActive()
        TrainerCall.onCreated(c)
        return c
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        TrainerCall.onFailed()
    }
}

class TrainerConnection : Connection() {
    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
    }

    // All of these mean "something else ended or interrupted the call": stop the session.
    override fun onDisconnect() = endedByOthers()
    override fun onAbort() = endedByOthers()
    override fun onHold() = endedByOthers()
    override fun onReject() = endedByOthers()

    private fun endedByOthers() {
        finish()
        TrainerCall.onEndedByOthers(this)
    }

    internal fun finish() {
        if (state != STATE_DISCONNECTED) {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
        }
    }
}
