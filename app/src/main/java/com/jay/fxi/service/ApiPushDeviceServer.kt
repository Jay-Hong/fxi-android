package com.jay.fxi.service

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.dto.DeviceRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [PushDeviceServer] over the protected API client, with no Android dependency so the production
 * mapping is judged on the JVM.
 *
 * A session holds one owner-bound snapshot, so its calls send without waiting for credentials
 * again. The transport still refuses a snapshot whose identity has moved on; that refusal is an
 * identity change, a cancellation, and never read as an answer — its status code included.
 */
internal class ApiPushDeviceServer(private val api: AuthenticatedApiClient) : PushDeviceServer {

    /**
     * Checks cancellation before it starts: the token provider fetches in its own scope, so a check
     * after the fetch began would not stop that fetch — only the call it would have served.
     */
    override suspend fun session(owner: AuthIdentityFence): PushServerSession? {
        currentCoroutineContext().ensureActive()
        return try {
            Session(api.captureSnapshot(owner))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private inner class Session(private val snapshot: AuthSnapshot) : PushServerSession {
        override suspend fun register(token: String): Boolean = try {
            api.registerDevice(snapshot, DeviceRequest(deviceToken = token, platform = PLATFORM_ANDROID)).isSuccessful
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

        override suspend fun unregister(token: String): DeleteResult = try {
            deleteResultOf(api.unregisterDevice(snapshot, token))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DeleteResult.FAILED
        }
    }

    private companion object {
        const val PLATFORM_ANDROID = "android"
    }
}

/**
 * A DELETE's answer. Only the endpoint's own "Device not found" says the row is absent for that
 * uid; any other 404 — an HTML page, an empty body, another meaning — keeps what is owed.
 */
internal fun deleteResultOf(response: AuthenticatedHttpResponse<Unit>): DeleteResult = when {
    response.isSuccessful -> DeleteResult.DELETED
    response.failure?.kind == AuthenticatedFailureKind.KNOWN_NOT_FOUND -> DeleteResult.ABSENT_FOR_UID
    else -> DeleteResult.FAILED
}
