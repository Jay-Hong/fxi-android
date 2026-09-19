package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SCHEMA
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.DEMAND
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ControlDiscontinuityObservationTest(private val variant:String) {
    @get:Rule val folder=TemporaryFolder()
    @Test fun eachActualNonV2ObservationCountsBeforeReturning()=runBlocking {
        val o=ControlStoreTestStorage(File(folder.root,"count.preferences_pb"))
        try {
            o.seed(schema=if(variant=="v1") 1 else 2)
            o.data.edit {when(variant){"absent"->it.clear();"malformed"->it[DEMAND]="broken";"future"->it[SCHEMA]=3}}
            val t=ControlCommandTracking.forOwner(o.owner);val c=o.control.prepare()
            assertEquals(BigInteger.ZERO,t.evidenceDiscontinuityCount);assertNull(t.findPrepared(c)!!.firstConfirmDiscontinuityCount)
            for(n in 1L..2L){
                o.control.execute(c)
                assertEquals(BigInteger.valueOf(n),t.evidenceDiscontinuityCount)
                assertNull(t.findPrepared(c)!!.firstConfirmDiscontinuityCount)
            }
        }finally{o.close()}
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases()=listOf("v1","absent","malformed","future").map{arrayOf(it)}
    }
}
