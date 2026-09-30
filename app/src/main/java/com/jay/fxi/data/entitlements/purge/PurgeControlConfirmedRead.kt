package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.RestartRead
import java.io.IOException

/** Confirms one owner snapshot before decoding control and purge obligations. */
internal class PurgeControlConfirmedRead(
    private val owner: DataStoreAccessEpochStore,
    private val reader: PurgeControlSnapshotReader = PurgeControlSnapshotReader()
) {
    suspend fun read(): RestartRead {
        val transaction = try {
            owner.transactRecord { snapshot -> RecordTransactionDecision.Confirm(snapshot, Unit) }
        } catch (_: IOException) {
            return RestartRead.Unconfirmed
        }
        return RestartRead.Confirmed(reader.read(transaction.snapshot))
    }
}
