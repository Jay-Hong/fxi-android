package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SCHEMA
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.HOLD
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.DEMAND
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ControlSchemaUpgradeRejectionTest(private val variant:String) {
    @get:Rule val folder=TemporaryFolder()
    @Test fun independentlyRejectsUnsupportedInput()=runBlocking {
        val o=ControlStoreTestStorage(File(folder.root,"upgrade.preferences_pb"))
        try {
            o.seed(schema=1)
            o.data.edit {when(variant){
                "absent"->it.clear();"future"->it[SCHEMA]=3;"payload-missing"->it.remove(HOLD)
                "malformed"->it[DEMAND]="broken";"schema-missing"->it.remove(SCHEMA)
                "schema-type"->it[androidx.datastore.preferences.core.stringPreferencesKey("control_schema")]="1"
                else->it[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)]="[]"
            }}
            val before=o.raw();val writes=o.storage.writes
            val observed=o.control.upgradeControlSchemaV1ToV2()
            assertEquals(variant,ControlSchemaUpgradeResult.RecoveryRequired::class.java,observed.javaClass)
            val result=observed as ControlSchemaUpgradeResult.RecoveryRequired
            assertEquals(if(variant=="absent") RecoveryReason.MigrationOrRecovery else RecoveryReason.UnreadableRecord,result.reason)
            assertEquals(before,result.observation.original);assertEquals(before,o.raw());assertEquals(writes,o.storage.writes)
        } finally {o.close()}
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases()=listOf("absent","future","payload-missing","malformed","schema-missing","schema-type","mixed-v1").map{arrayOf(it)}
    }
}
