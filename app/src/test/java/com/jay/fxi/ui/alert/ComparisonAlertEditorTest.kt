package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.*
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.domain.model.ComparisonAlertSetting
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComparisonAlertEditorTest {
    private class Rig(scope: CoroutineScope) : ComparisonAlertSectionSource {
        val id = AuthIdentityFence("a",1)
        val row = comparisonFixture()
        override val state = MutableStateFlow(ComparisonAlertSettingsState())
        val grants = mutableListOf<() -> Unit>()
        val calls = mutableListOf<String>()
        val identities = mutableListOf<AuthIdentityFence>()
        val origins = mutableListOf<ComparisonAlertPair>()
        val handles = mutableListOf<ComparisonAlertHandle>()
        var next: ComparisonAlertOutcome = ComparisonAlertOutcome.Pending
        var during: () -> Unit = {}
        var live = true
        var history = false
        val holder = ComparisonAlertSectionHolder(this,id,"tether","absolute",scope,{ live },{ history },{ emptyMap() },{ callback -> grants += callback })
        init { publish(row) }
        fun publish(vararg rows: ComparisonAlertSetting) {
            state.value = ComparisonAlertSettingsState(AlertLoadPhase.Ready,id,ComparisonAlertRowPolicy.adopt("a",rows.toList()))
            holder.reconcile()
        }
        fun opened(): ComparisonOpening {
            holder.edit(row)
            assertNotNull(holder.editor)
            return holder.editor!!
        }
        fun handle(call: String): ComparisonAlertHandle {
            calls += call
            val h = ComparisonAlertHandle()
            handles += h
            val callback = during
            during = {}
            callback()
            if (next != ComparisonAlertOutcome.Pending) h.finish(next)
            return h
        }
        override fun ensure() { calls += "ensure" }
        override fun authoritative() { calls += "authoritative" }
        override fun add(identity: AuthIdentityFence, draft: ComparisonAlertDraft): ComparisonAlertHandle {
            identities += identity
            return handle("add")
        }
        override fun edit(identity: AuthIdentityFence, oldId: Int, originPair: ComparisonAlertPair, draft: ComparisonAlertDraft): ComparisonAlertHandle {
            identities += identity
            origins += originPair
            return handle("edit $oldId")
        }
        override fun delete(identity: AuthIdentityFence, id: Int): ComparisonAlertHandle {
            identities += identity
            return handle("delete $id")
        }
        override fun setEnabled(identity: AuthIdentityFence, id: Int, enabled: Boolean): ComparisonAlertHandle {
            identities += identity
            return handle("toggle $id $enabled")
        }
    }

    @Test fun V01_duplicateRecheckedAtSubmit() = runTest {
        val r = Rig(backgroundScope)
        val token = r.opened()
        val save = { r.holder.save(token) }
        assertTrue(r.holder.canSave(token))
        r.publish(r.row,r.row.copy(id=52))
        assertEquals(comparisonDuplicate,r.holder.message(token))
        assertFalse(r.holder.canSave(token))
        save()
        assertTrue(r.calls.isEmpty())
        r.publish(r.row)
        save()
        assertEquals(listOf("edit 41"),r.calls)
        r.handles.single().finish(ComparisonAlertOutcome.Saved(r.row))
    }

    @Test fun V02_publishedCountControlsAvailability() = runTest {
        val r = Rig(backgroundScope)
        val h = r.holder
        for (phase in listOf(AlertLoadPhase.Idle,AlertLoadPhase.Loading,AlertLoadPhase.Retrying,AlertLoadPhase.TerminalError(AlertLoadFailure.Forbidden))) {
            r.state.value = ComparisonAlertSettingsState(phase,r.id)
            h.add()
            assertFalse(h.addAvailable)
        }
        assertTrue(r.grants.isEmpty())
        r.publish(r.row)
        r.state.value = r.state.value.copy(identity = AuthIdentityFence("a", 2))
        h.add()
        assertFalse(h.addAvailable)
        assertTrue(r.grants.isEmpty())
        fun fill(n: Int) {
            val rows = (1..n).map { i ->
                when (i%5) {
                    0 -> comparisonFixture(i,"jpy")
                    1 -> comparisonFixture(i,"eur")
                    2 -> comparisonFixture(i,"usd")
                    3 -> comparisonFixture(i,signed=true)
                    else -> comparisonFixture(i)
                }.copy(isEnabled=i%2==0,triggered=i%3==0)
            }
            r.publish(*(rows + listOf(r.row.copy(id=88),r.row.copy(id=88),r.row.copy(userId="b",id=99))).toTypedArray())
        }
        for (n in 26..31) {
            fill(n)
            assertEquals(n,r.state.value.adopted?.count)
            assertEquals(n < 30,h.addAvailable)
            assertEquals(if (n in 27..29) "(${30-n}개 남음)" else null,h.remaining)
        }
        h.add()
        assertTrue(r.grants.isEmpty())
        fill(29)
        h.add()
        r.grants.last()()
        val token = h.editor!!
        for (name in listOf("coinone", "korbit")) h.change(token) { f, q -> f.select(name to "usdt-krw", false, q) }
        assertTrue(h.canSave(token))
        val fields = token.fields
        fill(30)
        assertFalse(h.canSave(token))
        h.save(token)
        assertTrue(r.calls.isEmpty())
        assertEquals(fields, token.fields)
        assertEquals(comparisonCap,h.message(token))
        assertSame(token,h.editor)
        h.dismiss(token)
        val target = r.state.value.adopted!!.project("tether","absolute").first()
        h.edit(target)
        val edit = h.editor!!
        h.change(edit) { f,q -> f.select("gopax" to "usdt-krw",false,q) }
        assertTrue(h.canSave(edit))
        h.save(edit)
        assertEquals(listOf("edit ${target.id}"), r.calls)
        assertEquals(listOf(target.originalPair()), r.origins)
        assertEquals(listOf(r.id), r.identities)
    }

    @Test fun V03_onlyLivePermissionContinuationOpens() = runTest {
        val r = Rig(backgroundScope)
        val h = r.holder
        h.add()
        assertNull(h.editor)
        h.add()
        assertEquals(2,r.grants.size)
        r.grants.first()()
        assertNull(h.editor)
        r.history = true
        r.grants.last()()
        assertNull(h.editor)
        r.history = false
        for (loss in listOf("identity","cap","collapse")) {
            r.publish(r.row)
            h.add()
            when (loss) {
                "hidden" -> r.live=false
                "identity" -> r.state.value=r.state.value.copy(identity=AuthIdentityFence("a",2))
                "cap" -> r.publish(*(1..30).map { r.row.copy(id=it) }.toTypedArray())
                else -> { h.expand(false); h.expand(true) }
            }
            r.grants.last()()
            assertNull(h.editor)
            r.live=true
        }
        r.publish(r.row)
        h.add()
        val grant = r.grants.last()
        grant()
        val token = h.editor
        assertNotNull(token)
        h.dismiss(token!!)
        grant()
        assertNull(h.editor)
        assertTrue(r.calls.isEmpty())
        val ports = r.grants.size
        val edit = r.opened()
        r.next = ComparisonAlertOutcome.Saved(r.row)
        h.save(edit)
        h.toggle(r.row, false)
        h.askDelete(r.row)
        h.delete(h.confirm!!)
        assertEquals(listOf("edit 41", "toggle 41 false", "delete 41"), r.calls)
        assertEquals(ports, r.grants.size)

        var immediate = 0
        val now = ComparisonAlertSectionHolder(r, r.id, "tether", "absolute", backgroundScope,
            { true }, { false }, { emptyMap() }) { granted ->
            immediate++
            granted()
        }
        now.add()
        assertEquals(1, immediate)
        assertNotNull(now.editor)

        for (loss in listOf("hidden","generation","retired")) {
            val other=Rig(backgroundScope)
            other.holder.add()
            val late=other.grants.single()
            when (loss) {
                "hidden" -> other.live=false
                "generation" -> other.state.value=other.state.value.copy(identity=AuthIdentityFence("a",2))
                else -> other.holder.retire()
            }
            late()
            other.live=true
            other.publish(other.row)
            late()
            assertNull(other.holder.editor)
            assertTrue(other.calls.isEmpty())
        }
    }

    @Test fun V04_oneHandlePerSaveAttempt() = runTest {
        val r = Rig(backgroundScope)
        val token = r.opened()
        val save = { r.holder.save(token) }
        r.during = save
        save()
        assertEquals(listOf("edit 41"),r.calls)
        assertTrue(token.pending)
        save()
        assertEquals(1,r.handles.size)
        r.handles.single().finish(ComparisonAlertOutcome.Saved(r.row))
        runCurrent()
        assertNull(r.holder.editor)
        r.opened()
        save()
        assertEquals(1,r.calls.size)
    }

    @Test fun V05_partialReplacementLatchesOpening() = runTest {
        val r = Rig(backgroundScope)
        val h = r.holder
        val token = r.opened()
        h.save(token)
        val new = r.row.copy(id=83)
        r.publish(new,r.row)
        assertNull(h.message(token))
        r.handles.single().finish(ComparisonAlertOutcome.OldNotDeleted(41,new,Exception()))
        runCurrent()
        assertSame(token,h.editor)
        assertTrue(token.latched)
        h.change(token) { f,_ -> f.step(1) }
        assertFalse(h.canSave(token))
        assertEquals(comparisonPartialFailure,h.message(token))
        h.save(token)
        assertEquals(1,r.calls.size)
        r.publish(new)
        assertNull(h.editor)
    }

    @Test fun V06_terminalResultsAndRowMessages() = runTest {
        val r = Rig(backgroundScope)
        val h = r.holder
        val token = r.opened()
        val failure = ComparisonAlertOutcome.Failed(Exception("secret-body-41"))
        r.next = failure
        h.save(token)
        r.publish(r.row,r.row.copy(id=2))
        assertEquals("저장에 실패했습니다. 잠시 후 다시 시도해주세요.",h.message(token))
        r.publish(r.row)
        assertTrue(h.canSave(token))
        val messages = mapOf(ComparisonAlertNotSentReason.NOT_READY to "알림 목록을 다시 확인해 주세요.",
            ComparisonAlertNotSentReason.UNSUPPORTED to "지원하지 않는 알림 조건입니다.",
            ComparisonAlertNotSentReason.BUSY to "처리 중인 알림입니다.",ComparisonAlertNotSentReason.UNADMITTED to "접근 권한이 없습니다.")
        for ((reason,text) in messages) {
            r.next = ComparisonAlertOutcome.NotSent(reason)
            h.save(token)
            assertEquals(text,h.message(token))
            if (reason == ComparisonAlertNotSentReason.UNSUPPORTED) {
                assertFalse(h.canSave(token))
                h.change(token) { f,_ -> f.step(1) }
                assertTrue(h.canSave(token))
            }
        }
        r.next = ComparisonAlertOutcome.Pending
        h.save(token)
        assertNull(h.message(token))
        r.handles.last().finish(ComparisonAlertOutcome.Withdrawn)
        runCurrent()
        assertNull(h.editor)
        r.next = ComparisonAlertOutcome.NotSent(ComparisonAlertNotSentReason.BACKGROUND)
        h.save(r.opened())
        assertNull(h.editor)
        h.add(); r.grants.last()()
        val add = h.editor!!
        for (name in listOf("coinone","korbit")) h.change(add) { f,q -> f.select(name to "usdt-krw",false,q) }
        r.next = ComparisonAlertOutcome.NotSent(ComparisonAlertNotSentReason.CAP)
        h.save(add)
        assertEquals(comparisonCap,h.message(add))
        h.dismiss(add)
        r.next=failure
        h.toggle(r.row,false)
        assertEquals("toggle 41 false",r.calls.last())
        assertEquals("알림 변경에 실패했습니다.",h.rowMessage)
        r.next=ComparisonAlertOutcome.NotSent(ComparisonAlertNotSentReason.BUSY)
        h.toggle(r.row,true)
        assertEquals("처리 중인 알림입니다.",h.rowMessage)
        r.next=failure
        h.askDelete(r.row); h.delete(h.confirm!!)
        assertEquals("delete 41",r.calls.last())
        assertEquals("알림을 삭제하지 못했습니다.",h.rowMessage)
        r.next=ComparisonAlertOutcome.Deleted(41)
        h.askDelete(r.row); h.delete(h.confirm!!)
        assertNull(h.rowMessage)
        assertTrue(r.calls.none { it=="ensure" || it=="authoritative" })
    }

    @Test fun V07_retiredCallbacksCannotAffectNewOpening() = runTest {
        val r=Rig(backgroundScope)
        val h=r.holder
        val old=r.opened()
        h.save(old)
        r.publish()
        assertSame(old,h.editor)
        h.dismiss(old)
        r.publish(r.row)
        val fresh=r.opened()
        r.handles.single().finish(ComparisonAlertOutcome.Failed(Exception()))
        runCurrent()
        assertSame(fresh,h.editor)
        assertNull(h.message(fresh))
        h.save(old)
        assertEquals(1,r.calls.size)
        r.publish(r.row.copy(threshold=99.0,isEnabled=false,triggered=true))
        assertEquals(2.0,fresh.fields.threshold,0.0)
        r.publish(r.row.copy(leftSource="coinone"))
        assertNull(h.editor)
        r.publish(r.row)
        h.askDelete(r.row)
        val confirm=h.confirm!!
        r.publish()
        assertNull(h.confirm)
        r.publish(r.row)
        h.askDelete(r.row)
        h.delete(confirm)
        assertNotNull(h.confirm)
        h.retire()
        h.delete(confirm)
        assertEquals(1,r.calls.size)
    }
}
