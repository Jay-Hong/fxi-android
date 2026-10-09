package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.GraphDataMarking
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime

/**
 * S4 CUT-P2: the production [GraphV2PrepareWrite] over the issuer's marking. It asks [mark] exactly once with the capture's
 * fence, lifetime and KRX epoch. A marked record prepares GENERAL; KRX only when the capture names a non-null KRX epoch equal
 * to the record's and the record carries the KRX marker. A refusal blocks both with its reason and cause. Cancellation
 * propagates. Dormant: nothing in production constructs it.
 */
internal class IssuerGraphWritePreparation(
    private val mark: suspend (TopicSessionFence, TopicUseLifetime, String?, Boolean) -> GraphDataMarking
) : GraphV2PrepareWrite {
    override suspend fun prepare(captured: GraphV2AccessCapture, wantsKrx: Boolean): GraphV2WritePreparation =
        when (val marking = mark(captured.fence, captured.lifetime, captured.krxCapabilityEpoch, wantsKrx)) {
            is GraphDataMarking.Marked -> {
                val record = marking.record
                val krxMarked = captured.krxCapabilityEpoch != null &&
                    record.krxCapabilityEpoch == captured.krxCapabilityEpoch && record.mayContainKrxData
                GraphV2WritePreparation(
                    general = GraphV2NamespacePreparation.Ready(record),
                    krx = when {
                        !wantsKrx -> null
                        krxMarked -> GraphV2NamespacePreparation.Ready(record)
                        else -> GraphV2NamespacePreparation.Blocked("KRX not marked")
                    }
                )
            }
            is GraphDataMarking.Refused -> {
                val blocked = GraphV2NamespacePreparation.Blocked(marking.reason, marking.cause)
                GraphV2WritePreparation(general = blocked, krx = if (wantsKrx) blocked else null)
            }
        }
}
