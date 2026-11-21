package com.jay.fxi.ui.alert

import android.content.Context
import com.jay.fxi.data.alerts.AlertRateLimitEvidence
import com.jay.fxi.data.alerts.AlertSettingsUse
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.dto.SourceAlertHistoryItem
import com.jay.fxi.data.repository.AuthBoundResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * S8-4b harness (`R4c/S8/s8_4b_contract.agreed.r1.md` §3): a tether history wiring that counts its makings and its reads and
 * whose reads the test answers. A read ends only when the test answers it, whatever happened to its job meanwhile (as a
 * transport can), and counts the holder's folds of its answer, so a late answer is seen to reach the holder. [access] is given
 * the factory's display owner; by default it refuses every use, so a harness that only passes through the tether tab never
 * reads. Route harnesses that reach the tether tab pass it, so the production wiring is never resolved in a test.
 */
internal class CountingSourceHistoryWiring(
    private val access: (displayOwner: () -> TopicDisplayOwner?) -> SourceHistoryAccess = { Refusing }
) : SourceHistoryWiringFactory {
    val made = AtomicInteger()
    val reads = CopyOnWriteArrayList<Read>()
    val accessRevisions = MutableStateFlow(0L)
    val deletionRevisions = MutableStateFlow(0L)

    class Read(val identity: AuthIdentityFence, val asset: String, val guard: () -> Boolean) {
        @Volatile var continuation: Continuation<AuthBoundResult<List<SourceAlertHistoryItem>>>? = null
        /** The holder folded what this read returned: the answer reached the holder. */
        val folded = AtomicInteger()
        /** The read returned or threw into the holder's request. */
        @Volatile var finished = false

        fun answer(result: Result<List<SourceAlertHistoryItem>>) =
            resume(Result.success(AuthBoundResult({ folded.incrementAndGet() }, result)))

        /** Ends a read the test left waiting, so no request coroutine stays suspended past the test. */
        fun release() {
            if (continuation != null) resume(Result.failure(CancellationException("test end")))
        }

        private fun resume(with: Result<AuthBoundResult<List<SourceAlertHistoryItem>>>) {
            val waiting = checkNotNull(continuation) { "the read is not waiting" }
            continuation = null
            waiting.resumeWith(with)
        }
    }

    object Refusing : SourceHistoryAccess {
        override fun acquire(owner: TopicDisplayOwner): AlertSettingsUse? = null
        override fun admits(owner: TopicDisplayOwner, use: AlertSettingsUse) = false
    }

    override fun create(context: Context, displayOwner: () -> TopicDisplayOwner?): SourceHistoryWiring {
        made.incrementAndGet()
        return SourceHistoryWiring(
            access = access(displayOwner),
            read = SourceHistoryRead { identity, asset, guard ->
                val read = Read(identity, asset, guard).also { reads += it }
                try {
                    suspendCoroutine { read.continuation = it }
                } finally {
                    read.finished = true
                }
            },
            floor = object : SourceHistoryFloor {
                override fun remainingMillis() = 0L
                override fun record(evidence: AlertRateLimitEvidence) = Unit
            },
            accessRevisions = accessRevisions,
            deletionRevisions = deletionRevisions
        )
    }
}
