package com.teslalink.app.protocol

import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage

/** Port of pkg/protocol/error.go. */
sealed class TeslaError(message: String) : Exception(message) {
    /** The command may have been executed despite the error. */
    abstract val mayHaveSucceeded: Boolean

    /** The error is transient; the command can simply be retried. */
    abstract val temporary: Boolean

    class Busy : TeslaError("车辆忙或正在唤醒") {
        override val mayHaveSucceeded = false
        override val temporary = true
    }

    class NotConnected : TeslaError("未连接到车辆") {
        override val mayHaveSucceeded = false
        override val temporary = false
    }

    class NoSession : TeslaError("尚未与车辆建立会话") {
        override val mayHaveSucceeded = false
        override val temporary = false
    }

    class KeyNotPaired : TeslaError("公钥未与车辆配对") {
        override val mayHaveSucceeded = false
        override val temporary = false
    }

    class BadResponse(detail: String) : TeslaError("无效的车辆响应: $detail") {
        override val mayHaveSucceeded = false
        override val temporary = false
    }

    class VehicleError(
        val fault: UniversalMessage.MessageFault_E,
        val canRetryWithUpdatedSession: Boolean,
    ) : TeslaError("协议错误: ${fault.name} (${fault.number})") {
        override val mayHaveSucceeded =
            fault == UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_NONE ||
                fault == UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED
        override val temporary = fault in retriableFaults
    }

    /** The vehicle received and authenticated a command but refused to execute it. */
    class Nominal(detail: String) : TeslaError("车辆拒绝执行: $detail") {
        override val mayHaveSucceeded = false
        override val temporary = false
    }

    class Keychain(detail: String) : TeslaError("钥匙操作失败: $detail") {
        override val mayHaveSucceeded = false
        override val temporary = false
    }

    class Timeout(mayHaveSucceeded: Boolean) : TeslaError("等待车辆响应超时") {
        override val mayHaveSucceeded = mayHaveSucceeded
        override val temporary = false
    }

    companion object {
        val retriableFaults: Set<UniversalMessage.MessageFault_E> = setOf(
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_BUSY,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_TIMEOUT,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_TIME_TO_LIVE_TOO_LONG,
        )
    }
}

fun shouldRetry(err: Throwable): Boolean =
    err is TeslaError && !err.mayHaveSucceeded && err.temporary

/** Port of protocol.GetError. */
fun protocolErrorOf(message: UniversalMessage.RoutableMessage): TeslaError? {
    val fault = message.signedMessageStatus.signedMessageFault
    if (fault != UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_NONE) {
        if (fault == UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID) {
            return TeslaError.KeyNotPaired()
        }
        return TeslaError.VehicleError(fault, message.hasSessionInfo())
    }
    val encodedInfo = if (message.hasSessionInfo()) message.sessionInfo else null
    if (encodedInfo != null) {
        val info = try {
            Signatures.SessionInfo.parseFrom(encodedInfo)
        } catch (e: Exception) {
            return TeslaError.BadResponse("session info")
        }
        return when (info.status) {
            Signatures.Session_Info_Status.SESSION_INFO_STATUS_OK -> null
            Signatures.Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST ->
                TeslaError.KeyNotPaired()
            else -> TeslaError.BadResponse("unknown session info status")
        }
    }
    return when (message.signedMessageStatus.operationStatus) {
        UniversalMessage.OperationStatus_E.OPERATIONSTATUS_OK -> null
        UniversalMessage.OperationStatus_E.OPERATIONSTATUS_WAIT -> TeslaError.Busy()
        // Go treats this as "not a protocol-layer error": it's only sent for legacy clients and the
        // real outcome is carried in the application payload. Failing here would reject successes.
        UniversalMessage.OperationStatus_E.OPERATIONSTATUS_ERROR -> null
        null -> TeslaError.BadResponse("missing operation status")
        else -> TeslaError.BadResponse("unknown operation status")
    }
}

/** Port of internal/authentication/window.go. */
class SlidingWindow {
    private var history: ULong = 0uL
    private var counter: Long = 0
    private var used = false

    /** Returns false if [newCounter] was already used (replay). */
    @Synchronized
    fun update(newCounter: Long): Boolean {
        if (!used) {
            used = true
            counter = newCounter
            return true
        }
        val c = counter
        val w = history
        if (newCounter == c) return false
        if (newCounter < c) {
            val age = c - newCounter
            if (age > WINDOW_SIZE) return false
            if (((w shr (age - 1).toInt()) and 1uL) == 1uL) return false
            history = w or (1uL shl ((age - 1).toInt()))
            return true
        }
        val shift = newCounter - c
        counter = newCounter
        history = (w shl shift.toInt()) or (1uL shl (shift.toInt() - 1))
        return true
    }

    companion object {
        const val WINDOW_SIZE = 32L
    }
}
