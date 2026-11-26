package com.jay.fxi

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.Lifecycle
import com.jay.fxi.data.alerts.*
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.dto.ComparisonAlertRequest
import com.jay.fxi.data.remote.dto.ComparisonAlertUpdateRequest
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.domain.model.ComparisonAlertSetting
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.ui.alert.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ComparisonAlertEditorRouteTest {
    private val route = ComparisonAlertSectionRouteTest()
    @get:Rule val rule = route.rule
    private val id = route.a1
    private val premium = MutableStateFlow(OwnedPremiumAccess(id.uid,id.authGeneration,PremiumAccessState.PremiumConfirmed))
    private val access = AlertSettingsAccess({ id },{ route.graphFence },SnapshotTopicUseAuthority { route.snapshot },
        { premium.value },{ true },GraphProtectedAdmission({ id },DeletionAdmissionStore()))
    private val old = route.row(41,"tether","signed")
    private val absolute = route.row(42,"tether")
    private val rows = mutableListOf<ComparisonAlertSetting>()
    private var nextId = 83
    private data class Call(val method: String, val identity: AuthIdentityFence, val admitted: Boolean, val id: Int?, val request: Any?)
    private val calls = CopyOnWriteArrayList<Call>()
    private val handles = mutableListOf<ComparisonAlertHandle>()
    private val gates = mutableMapOf<String,CompletableDeferred<Int>>()
    private val held = mutableListOf<CompletableDeferred<Int>>()
    private var grants = 0
    private var grant = true
    private var duringGrant: () -> Unit = {}
    private val permission = NotificationPermissionEnvironment({ true },{ id },premium,MutableStateFlow(NotificationCapability.GRANTED),
        { false },{ grants++; duringGrant(); grant })
    private lateinit var owner: ComparisonAlertSettingsOwner
    private lateinit var tester: StateRestorationTester

    private suspend fun <T> answer(method: String, use: AlertSettingsUse, guard: () -> Boolean, id: Int? = null,
        request: Any? = null, echo: () -> T): AuthBoundResult<T> {
        calls += Call(method,use.identity,guard(),id,request)
        val status = gates.remove(method)?.await() ?: 200
        val result = if (status == 200) Result.success(echo()) else Result.failure(AlertRepositoryException("secret-body-41",
            AuthenticatedHttpFailure(status, Headers.Builder().build(), byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP)))
        return AuthBoundResult({},result)
    }

    @Composable private fun Content() {
        CompositionLocalProvider(LocalNotificationPermissionEnvironment provides permission) { route.RouteContent() }
    }

    private fun start(initial: List<ComparisonAlertSetting> = listOf(old,absolute)) {
        rows += initial
        rule.runOnUiThread {
            owner = ComparisonAlertSettingsOwner({ use,guard -> answer("GET",use,guard) { rows.toList() } },
                access,{ id },premium,MutableStateFlow(0L),route.scope,{ delay(it) },{ 0.0 },
                post = { use,req,guard -> answer("POST",use,guard,request=req) {
                    old.copy(id=nextId++,userId=use.identity.uid,tab=req.tab,diffType=req.diffType,
                        leftSource=req.leftSource,leftAsset=req.leftAsset,rightSource=req.rightSource,rightAsset=req.rightAsset,
                        operator=req.operator,threshold=req.threshold,repeatIntervalSec=req.repeatIntervalSec,isEnabled=req.isEnabled,
                        triggered=false).also { rows.add(0,it) }
                } },
                put = { use,target,req,guard -> answer("PUT",use,guard,target,req) {
                    val previous=rows.firstOrNull { it.id==target } ?: old.copy(id=target)
                    previous.copy(isEnabled=req.isEnabled ?: previous.isEnabled,operator=req.operator ?: previous.operator,
                        threshold=req.threshold ?: previous.threshold, repeatIntervalSec=req.repeatIntervalSec.let { value ->
                            if (value == null) previous.repeatIntervalSec else value.jsonPrimitive.intOrNull }).also { next ->
                        rows.replaceAll { if (it.id==target) next else it }
                    }
                } },
                remove = { use,target,guard -> answer("DELETE",use,guard,target) { rows.removeAll { it.id==target }; Unit } })
            val real=OwnerComparisonSectionSource(owner)
            route.factory.reader=object : ComparisonAlertSectionSource by real {
                override fun add(identity: AuthIdentityFence,draft: ComparisonAlertDraft) = real.add(identity,draft).also(handles::add)
                override fun edit(identity: AuthIdentityFence,oldId:Int,originPair: ComparisonAlertPair,draft: ComparisonAlertDraft) =
                    real.edit(identity,oldId,originPair,draft).also(handles::add)
                override fun delete(identity: AuthIdentityFence,id:Int) = real.delete(identity,id).also(handles::add)
                override fun setEnabled(identity: AuthIdentityFence,id:Int,enabled:Boolean) = real.setEnabled(identity,id,enabled).also(handles::add)
            }
            owner.onForeground(false)
        }
        tester=StateRestorationTester(rule)
        tester.setContent { Content() }
        record("GET")
    }

    private fun hold(method:String): CompletableDeferred<Int> = CompletableDeferred<Int>().also {
        rule.runOnUiThread { gates[method]=it; held+=it }
    }
    private fun release(gate:CompletableDeferred<Int>, status:Int=200) { gate.complete(status); rule.waitForIdle() }
    private fun ids() = owner.state.value.adopted?.rows?.map { it.id }
    private fun <T> observe(expected:T, read:()->T) {
        val end=android.os.SystemClock.elapsedRealtime()+5000
        var last=read()
        while (last!=expected && android.os.SystemClock.elapsedRealtime()<end) {
            android.os.SystemClock.sleep(20)
            last=read()
        }
        assertEquals(expected,last)
    }
    private fun record(vararg verbs:String) = observe(verbs.toList()) { calls.map { it.method } }
    private fun tags(tag:String) = rule.onAllNodesWithTag(tag,true).fetchSemanticsNodes()
    private fun count(tag:String,n:Int) = observe(n) { tags(tag).size }
    private fun texts(label:String) = rule.onAllNodesWithText(label,useUnmergedTree=true).fetchSemanticsNodes().size
    private fun text(label:String,n:Int=1) = observe(n) { texts(label) }
    private fun action(matcher:SemanticsMatcher): ()->Boolean {
        val nodes=rule.onAllNodes(matcher and hasClickAction(),true).fetchSemanticsNodes()
        assertEquals(matcher.description,1,nodes.size)
        val callback=nodes.first().config.getOrNull(SemanticsActions.OnClick)?.action
        assertNotNull(callback)
        return callback!!
    }
    private fun kept(tag:String) = action(hasTestTag(tag))
    private fun act(callback:()->Boolean) { rule.runOnUiThread { callback() }; rule.waitForIdle() }
    private fun click(tag:String) = act(kept(tag))
    private fun button(label:String) {
        val matches=rule.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes()
        assertEquals(label,1,matches.size)
        act(matches.single().config[SemanticsActions.OnClick].action!!)
    }
    private fun edit(id:Int) = click("comparison-edit-$id")
    private fun save() = click("comparison-save")
    private fun add(kind:String="signed") = click("comparison-$kind-add")
    private fun pick(name:String) = click("comparison-pick-$name")
    private fun sheet(n:Int) = count("comparison-editor",n)
    private fun field() = tags("comparison-value").let {
        assertEquals(1,it.size)
        it.single().config[SemanticsProperties.Text].single().text
    }
    private fun refresh(rowsNow: List<ComparisonAlertSetting>) {
        val reads = calls.count { it.method == "GET" }
        rule.runOnUiThread { rows.clear(); rows.addAll(rowsNow); owner.authoritative() }
        observe(reads + 1) { calls.count { it.method == "GET" } }
        rule.waitForIdle()
    }
    private val warning="새 알림은 생성됐습니다. 기존 알림을 삭제하지 못했습니다. 목록에서 기존 알림을 직접 삭제해 주세요."

    @After fun finish() {
        try {
            held.forEach { it.complete(500) }
            assertTrue(calls.all { it.identity==id && it.admitted })
        } finally {
            route.tearDown()
        }
    }

    @Test fun UI01_pickerLabelsAndInitialDraft() {
        val fx=route.row(53,"jpy").copy(threshold=50.55)
        start(listOf(old,absolute,fx))
        add()
        sheet(1)
        rule.onNode(hasAnyAncestor(hasTestTag("comparison-editor")) and hasText("김프 알림"),true).assertExists()
        text("기준"); text("비교"); text("+0")
        pick("bithumb")
        rule.onNodeWithTag("comparison-pick-bithumb",true).assertIsSelected()
        assertEquals(0,rule.onAllNodes(hasAnyAncestor(hasTestTag("comparison-pick-bithumb")) and hasText("0"),true).fetchSemanticsNodes().size)
        fun publishQuotes(left: Boolean, right: Boolean) {
            val at = old.createdAt
            val quotes = listOfNotNull(if (left) TopicQuote("bithumb","usdt-krw",1386.0,at) else null,
                if (right) TopicQuote("investing","usd-krw",1400.0,at) else null)
            rule.runOnUiThread { route.display.value = route.display.value.copy(rates=com.jay.fxi.domain.model.TopicRates().merge(quotes)) }
            rule.waitForIdle()
        }
        publishQuotes(true,true)
        text("1386"); text("1400.00"); text("-14.0원"); text("-1.00%"); assertEquals("+0",field())
        for ((left,right) in listOf(true to false,false to true)) {
            publishQuotes(left,right)
            text("-14.0원",0); text("-1.00%",0)
            rule.onNodeWithTag("comparison-save",true).assertIsEnabled()
        }
        button("⋮"); button("넓은 범위 설정")
        text("넓은 범위 ±1000")
        text("비정상 급등락 감지용 넓은 범위(±1000)입니다. 시트를 닫으면 기본(±100)으로 돌아갑니다.")
        button("⋮")
        rule.onNodeWithText("넓은 범위 사용 중 (±1000)").assertIsNotEnabled()
        androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.waitForIdle()
        button("취소")
        edit(old.id); text("김프 알림 수정"); button("취소")
        route.select(FreeTab.JPY)
        add("absolute")
        text("비교할 은행 2곳을 선택해주세요")
        val cells=listOf("investing","kb","hana","shinhan","woori","ibk","nh","sc","bs").map { name ->
            val found=tags("comparison-pick-$name"); assertEquals(1,found.size); found.single().positionInRoot
        }
        assertEquals(1,cells.take(5).map { it.y }.distinct().size)
        assertEquals(1,cells.drop(5).map { it.y }.distinct().size)
        assertTrue(cells[0].y < cells[5].y)
        assertTrue(cells.take(5).zipWithNext().all { (a,b) -> a.x < b.x })
        rule.onNodeWithTag("comparison-save",true).assertIsNotEnabled()
        button("취소"); edit(53); text("비교 알림 수정")
        pick("woori")
        rule.onNodeWithTag("comparison-pick-kb",true).assertIsNotSelected()
        rule.onNode(hasAnyAncestor(hasTestTag("comparison-editor")) and hasText("50.55"),true).assertExists()
        save()
        record("GET","POST","DELETE"); sheet(0)
        assertEquals(50.55,(calls[1].request as ComparisonAlertRequest).threshold,0.0)
    }

    private fun touch(label:String, holdMs:Long=0, leave:Boolean=false) {
        val node=rule.onNodeWithTag("comparison-step-$label",true)
        node.performScrollTo()
        node.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(if (holdMs==0L) 16L else holdMs)
        if (leave) {
            node.performTouchInput { moveTo(Offset(center.x,-10f)) }
            rule.mainClock.advanceTimeBy(600)
        }
        node.performTouchInput { up() }
        rule.waitForIdle()
    }

    @Test fun UI02_stepperGesturesAndDuplicateGuard() {
        start(listOf(absolute.copy(threshold=998.0)))
        edit(42)
        touch("＋"); text("999")
        touch("＋",600); text("999")
        val beforeScroll = tags("comparison-value").single().positionInRoot
        touch("−",500,true)
        assertEquals(beforeScroll,tags("comparison-value").single().positionInRoot)
        assertTrue(field().toDouble() < 999)
        val value=tags("comparison-value").single().config[SemanticsProperties.Text].single().text
        rule.mainClock.advanceTimeBy(800)
        text(value); sheet(1)
        val save=kept("comparison-save")
        rule.runOnUiThread {
            rows+=absolute.copy(id=52,threshold=value.toDouble())
            owner.authoritative()
        }
        text("이미 등록된 알림이에요. 목록에서 켜서 사용하세요.")
        rule.onNodeWithTag("comparison-save",true).assertIsNotEnabled()
        act(save)
        record("GET","GET")
        assertTrue(handles.isEmpty())
        button("취소")
        val fx=route.row(52,"jpy").copy(threshold=50.55)
        for ((r,label,value) in listOf(
            Triple(absolute.copy(threshold=0.0),"−","0"),
            Triple(old.copy(threshold=100.0),"＋","+100"),
            Triple(old.copy(threshold=-100.0),"−","-100"),
            Triple(old.copy(threshold=1500.0),"＋","+1000"),
            Triple(old.copy(threshold=1500.0),"−","+1000"),
            Triple(old.copy(threshold=-1000.0),"−","-1000"),
            Triple(fx,"＋","50.6"),Triple(fx,"−","50.5"))) {
            refresh(listOf(r))
            route.select(if (r.tab=="jpy") FreeTab.JPY else FreeTab.TETHER)
            edit(r.id); touch(label); assertEquals(value,field()); button("취소")
        }
    }

    @Test fun UI03_addPermissionAndPublishedCap() {
        val many=(1..30).map { route.row(it,if (it%2==0) "eur" else "tether") }
        start(many)
        count("comparison-signed-add",0)
        text("최대 30개까지 설정할 수 있습니다",2)
        assertEquals(0,grants)
        for (n in 26..31) {
            refresh((many + route.row(31,"tether")).take(n))
            count("comparison-signed-add",if (n < 30) 1 else 0)
            for (remaining in 1..3) text("(${remaining}개 남음)",if (remaining==30-n) 2 else 0)
            if (n==31) count("comparison-row-31",1)
        }
        refresh(many.take(29))
        val beforeEdit = grants
        edit(1); sheet(1); button("취소")
        assertEquals(beforeEdit,grants)
        duringGrant = { rows += many.last(); owner.authoritative() }
        add()
        duringGrant = {}
        sheet(0)
        refresh(many.take(29))
        add(); sheet(1)
        refresh(many)
        rule.onNodeWithTag("comparison-save",true).assertIsNotEnabled()
        button("취소")
        val atCap = calls.size
        click("comparison-toggle-3")
        edit(5); pick("korbit"); save(); sheet(0)
        observe(listOf("PUT", "POST", "DELETE")) { calls.drop(atCap).map { it.method } }
        val prefix = calls.map { it.method }
        val priorGrants = grants
        val heldDelete=hold("DELETE")
        click("comparison-delete-1"); button("삭제")
        observe(prefix + "DELETE") { calls.map { it.method } }
        text("(1개 남음)",2)
        grant=false; add(); sheet(0)
        grant=true; add(); sheet(1)
        save()
        text("최대 30개까지 설정할 수 있습니다")
        observe(prefix + "DELETE") { calls.map { it.method } }
        assertEquals(priorGrants + 2,grants)
        release(heldDelete)
        save()
        observe(prefix + listOf("DELETE","POST")) { calls.map { it.method } }; sheet(0)
        edit(84); sheet(1); button("취소")
        assertEquals(priorGrants + 2,grants)
        val denied = CountingComparisonSource(ComparisonAlertSettingsState())
        rule.runOnUiThread { route.factory = denied }
        for (phase in listOf(AlertLoadPhase.Idle,AlertLoadPhase.Loading,AlertLoadPhase.Retrying,AlertLoadPhase.TerminalError(AlertLoadFailure.Forbidden))) {
            rule.runOnUiThread { denied.settings.value = ComparisonAlertSettingsState(phase,id) }
            count("comparison-signed-add",0)
        }
        rule.runOnUiThread { denied.settings.value = owner.state.value.copy(identity=AuthIdentityFence(id.uid,id.authGeneration + 1)) }
        count("comparison-absolute-add",0)
        assertEquals(priorGrants + 2,grants)
    }

    @Test fun UI04_singleSaveAndSamePairUpdate() {
        start(listOf(absolute.copy(isEnabled=false)))
        edit(42)
        val first=kept("comparison-save")
        val gate=hold("PUT")
        rule.runOnUiThread { first(); first() }
        record("GET","PUT")
        act(first)
        assertEquals(1,handles.size)
        assertEquals(42,calls.last().id)
        val request = calls.last().request as ComparisonAlertUpdateRequest
        assertEquals(true,request.isEnabled)
        assertEquals(absolute.threshold,request.threshold)
        assertEquals(absolute.operator,request.operator)
        count("comparison-save", 0)
        val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        val inSheet = hasAnyAncestor(hasTestTag("comparison-editor"))
        observe(1) { rule.onAllNodes(progress and inSheet, true).fetchSemanticsNodes().size }
        release(gate); sheet(0)
        add(); act(first); record("GET","PUT")
        assertEquals(1, handles.size)
        val create=kept("comparison-save")
        val post=hold("POST")
        act(create); record("GET","PUT","POST"); act(create)
        assertEquals(2,handles.size)
        release(post); sheet(0)
        assertEquals(listOf(83,42),ids())
        assertEquals(1,grants)
    }

    @Test fun UI05_replacementOrderAndPartialFailure() {
        start(listOf(old))
        edit(41); pick("kb")
        val saved=kept("comparison-save")
        val post=hold("POST"); val delete=hold("DELETE")
        act(saved); record("GET","POST")
        assertEquals(listOf(41),ids())
        release(post); record("GET","POST","DELETE")
        assertEquals(listOf(83,41),ids())
        val positions=listOf(83,41).map { id ->
            val nodes=tags("comparison-row-$id")
            assertEquals(1,nodes.size)
            val node=nodes.single()
            android.util.Log.i("UI05","row=$id semantics=${node.id} at=${node.positionInRoot} size=${node.size}")
            assertTrue(node.size.width > 0 && node.size.height > 0)
            node.positionInRoot.y
        }
        assertTrue(positions[0] < positions[1])
        text("이미 등록된 알림이에요. 목록에서 켜서 사용하세요.",0)
        release(delete,500); text(warning); sheet(1)
        text("이미 등록된 알림이에요. 목록에서 켜서 사용하세요.",0)
        touch("＋"); rule.onNodeWithTag("comparison-save",true).assertIsNotEnabled()
        act(saved); record("GET","POST","DELETE")
        assertEquals(1,handles.size)
        button("취소"); click("comparison-delete-41"); button("삭제")
        record("GET","POST","DELETE","DELETE")
        observe(listOf(83)) { ids() }
        assertEquals(listOf(41,41),calls.takeLast(2).map { it.id })
        for (status in listOf(200,404)) {
            refresh(listOf(old))
            val before=calls.size
            edit(41); pick("kb")
            val first=hold("POST")
            save()
            release(first,500)
            sheet(1); text("저장에 실패했습니다. 잠시 후 다시 시도해주세요.")
            assertEquals(listOf(41),ids())
            assertEquals(listOf("POST"),calls.drop(before).map { it.method })
            val removing=hold("DELETE")
            save()
            observe(listOf("POST","POST","DELETE")) { calls.drop(before).map { it.method } }
            val newId = ids()?.first()
            assertTrue(newId != null && newId != 41)
            release(removing,status)
            sheet(0); assertEquals(listOf(newId),ids())
        }
    }

    @Test fun UI06_independentRowActionsAndMessages() {
        start()
        edit(41); sheet(1); button("취소")
        edit(42)
        val initial = field()
        refresh(listOf(old,absolute.copy(threshold=9.0,triggered=true)))
        assertEquals(initial,field()); sheet(1)
        refresh(listOf(old,absolute.copy(rightSource="coinone")))
        sheet(0)
        click("comparison-delete-42")
        refresh(listOf(old))
        text("비교 알림을 삭제할까요?",0)
        refresh(listOf(old,absolute))
        val prefix=calls.map { it.method }
        val put=hold("PUT")
        click("comparison-toggle-42")
        observe(prefix + "PUT") { calls.map { it.method } }
        sheet(0)
        assertEquals(false,(calls.last().request as ComparisonAlertUpdateRequest).isEnabled)
        rule.onNodeWithTag("comparison-toggle-42",true).assertIsOff()
        click("comparison-toggle-42")
        text("처리 중인 알림입니다.")
        release(put,500)
        text("알림 변경에 실패했습니다.")
        rule.onNodeWithTag("comparison-toggle-42",true).assertIsOn()
        click("comparison-delete-41"); text("김프 알림을 삭제할까요?"); button("취소")
        click("comparison-delete-42"); text("비교 알림을 삭제할까요?")
        observe(prefix + "PUT") { calls.map { it.method } }
        val delete=hold("DELETE")
        button("삭제"); observe(prefix + listOf("PUT","DELETE")) { calls.map { it.method } }; count("comparison-row-42",0)
        click("comparison-toggle-41")
        rule.onNodeWithTag("comparison-toggle-41",true).assertIsOff()
        release(delete,500); text("알림을 삭제하지 못했습니다."); text("secret-body-41",0)
        rule.onNodeWithTag("comparison-toggle-41",true).assertIsOff()
        assertEquals(listOf(41,42),ids())
        click("comparison-delete-42")
        val gone=hold("DELETE")
        button("삭제"); release(gone,404)
        observe(listOf(41)) { ids() }
        assertEquals(0,grants)
        observe(prefix + listOf("PUT","DELETE","PUT","DELETE")) { calls.map { it.method } }
    }

    @Test fun UI07_openingRetirementAndRecreation() {
        start(listOf(old))
        val host = rule.activity
        val pausedAdd = kept("comparison-signed-add")
        var grantState: Lifecycle.State? = null
        duringGrant = { grantState = host.lifecycle.currentState }
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        try {
            rule.runOnUiThread { pausedAdd() }
        } finally {
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            duringGrant = {}
        }
        assertEquals(Lifecycle.State.STARTED, grantState)
        assertEquals(1, grants)
        sheet(1); button("취소")
        click("comparison-delete-41")
        val oldDelete=rule.onNode(hasClickAction() and hasText("삭제")).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        button("취소"); click("comparison-delete-41")
        act(oldDelete); text("김프 알림을 삭제할까요?"); record("GET")
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        text("김프 알림을 삭제할까요?")
        androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).pressBack()
        text("김프 알림을 삭제할까요?",0)
        click("comparison-delete-41")
        tester.emulateSavedInstanceStateRestore()
        text("김프 알림을 삭제할까요?",0)
        edit(41)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        sheet(1)
        tester.emulateSavedInstanceStateRestore()
        sheet(0)
        click("comparison-delete-41")
        val abaDelete = rule.onNode(hasClickAction() and hasText("삭제")).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        route.select(FreeTab.EUR); route.select(FreeTab.TETHER)
        text("김프 알림을 삭제할까요?",0)
        act(abaDelete); record("GET")
        assertTrue(handles.isEmpty())
        edit(41)
        val stale=kept("comparison-save")
        route.select(FreeTab.EUR); route.select(FreeTab.TETHER); edit(41)
        act(stale); record("GET"); sheet(1)
        val backgroundSave=kept("comparison-save")
        rule.runOnUiThread { owner.onBackground(); backgroundSave() }
        sheet(0); record("GET")
        rule.runOnUiThread { owner.onForeground(false) }
        edit(41); pick("kb")
        val delete=hold("DELETE")
        save(); record("GET","POST","DELETE")
        val handle=handles.last()
        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.onActivity { activity -> activity.setContent { Content() } }
        release(delete,500)
        observe(1) { handle.completions }
        sheet(0); text(warning,0)
        assertEquals(listOf(83,41),ids())
        record("GET","POST","DELETE")
        edit(41)
        val failed=hold("PUT")
        save()
        record("GET","POST","DELETE","PUT")
        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.onActivity { activity -> activity.setContent { Content() } }
        release(failed,500)
        sheet(0)
        text("저장에 실패했습니다. 잠시 후 다시 시도해주세요.",0)
        assertEquals(1,handles.last().completions)
    }
}
