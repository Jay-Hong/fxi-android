package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.recovery
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Each subcondition has its own test instance; invalid internal rows bypass earlier wire guards. */
@RunWith(Parameterized::class)
class ControlAppliedMatchTest(private val variant: String) {
    @get:Rule val folder = TemporaryFolder()
    @Test fun independentLinkageMismatch() = runBlocking {
        val o=ControlStoreTestStorage(File(folder.root,"match.preferences_pb"))
        try {
            o.seed(demand="[$request]")
            val action=o.control.addition(ControlKind.RECOVERY_INTENT) {id->literal(recovery);set("id",ControlScalar.Text(id))} as ControlMutation.Add
            val c=o.control.prepare(action,o.control.edit(ControlKind.DEMAND,node(request)) {})
            val t=TrackedControlCommand(c)
            t.targets.set(listOf(ControlCommandTarget(action.proposedId,(action.built as ControlWriteResult.Written).node,false),
                ControlCommandTarget("d",node(request),false)))
            val targets=listOf(AppliedTarget(0,ControlKind.RECOVERY_INTENT,action.proposedId,false,true),AppliedTarget(1,ControlKind.DEMAND,"d",false,false))
            fun row(id:String=c.id,life:String=c.ownerTrackingLifetimeId.value,ts:List<AppliedTarget> = targets) = AppliedEvidence.Mutations(id,life,ts)
            val good=row();assertTrue(ControlAppliedEvidence.matches(c,t,good))
            val bad: AppliedEvidence=when(variant){
                "lifetime"->row(life=OwnerTrackingLifetimeId.issue().value)
                "command"->row(id="other")
                "body"->AppliedEvidence.Rotation(c.id,c.ownerTrackingLifetimeId.value,listOf(action.proposedId),"d")
                "count"->row(ts=targets + targets[1].copy(index=2,id="extra"))
                "index"->row(ts=listOf(targets[0].copy(index=1),targets[1]))
                "kind"->row(ts=listOf(targets[0].copy(kind=ControlKind.SEAL),targets[1]))
                "id"->row(ts=listOf(targets[0].copy(id="other"),targets[1]))
                "joined"->row(ts=listOf(targets[0],targets[1].copy(joined=true)))
                "noop-written"->row(ts=listOf(targets[0],targets[1].copy(written=true)))
                "exact-written"->{
                    // Both values are structurally plausible; only the retained actual-change list differs.
                    t.expectedApplied=good
                    row(ts=listOf(targets[0].copy(written=false),targets[1]))
                }
                "missing-fixed"->{t.targets.set(listOf(null,t.targets.get()[1]));good}
                else->error(variant)
            }
            assertFalse(variant,ControlAppliedEvidence.matches(c,t,bad))
        } finally {o.close()}
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases()=listOf("lifetime","command","body","count","index","kind","id","joined","noop-written","exact-written","missing-fixed").map{arrayOf(it)}
    }
}
