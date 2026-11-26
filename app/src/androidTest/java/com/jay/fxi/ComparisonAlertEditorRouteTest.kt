package com.jay.fxi

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.ui.theme.*
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

    private val inEditor = hasAnyAncestor(hasTestTag("comparison-editor"))
    private fun editorCount(label: String, expected: Int) = observe(expected) {
        rule.onAllNodes(inEditor and hasText(label), true).fetchSemanticsNodes().size
    }

    private fun layout(label: String): TextLayoutResult {
        editorCount(label, 1)
        val results = mutableListOf<TextLayoutResult>()
        val node = rule.onNode(inEditor and hasText(label), true).fetchSemanticsNode()
        val getLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        assertTrue("$label layout", rule.runOnIdle { getLayout?.invoke(results) == true })
        assertEquals(label, 1, results.size)
        return results.single()
    }

    private fun style(label: String, color: Color, size: Int, weight: FontWeight,
        family: FontFamily = FontFamily.Default, digits: Boolean = false): TextLayoutResult {
        val result = layout(label)
        val actual = result.layoutInput.style
        assertEquals("$label color", color, actual.color)
        assertEquals("$label size", size.sp, actual.fontSize)
        assertEquals("$label weight", weight, actual.fontWeight)
        assertEquals("$label family", family, actual.fontFamily)
        if (digits) assertEquals(label, "tnum", actual.fontFeatureSettings)
        return result
    }

    private fun bounds(matcher: SemanticsMatcher): Rect {
        val nodes = rule.onAllNodes(inEditor and matcher, true).fetchSemanticsNodes()
        assertEquals(matcher.description, 1, nodes.size)
        val node = nodes.single()
        val origin = node.positionInRoot
        return Rect(origin.x, origin.y, origin.x + node.size.width, origin.y + node.size.height)
    }

    private fun box(tag: String) = bounds(hasTestTag("comparison-$tag"))
    private fun near(a: Float, b: Float) = assertEquals(a, b, 1f)
    private fun rates(vararg prices: Triple<String, String, Double>) {
        val quotes = prices.map { (code, asset, value) -> TopicQuote(code, asset, value, old.createdAt) }
        rule.runOnUiThread { route.display.value = route.display.value.copy(rates = TopicRates().merge(quotes)) }
        rule.waitForIdle()
    }

    private fun condition(above: Boolean) {
        val label = if (above) "△ 이상" else "▽ 이하"
        style(label, if (above) PositiveColor else NegativeColor, 15, FontWeight.SemiBold)
        editorCount(if (above) "▽ 이하" else "△ 이상", 0)
        val node = hasTestTag("comparison-condition") and inEditor and hasText(label) and hasClickAction()
        assertEquals(1, rule.onAllNodes(node).fetchSemanticsNodes().size)
    }

    private fun input(value: String, color: Color) {
        assertEquals(value, field())
        assertEquals(1, style(value, color, 24, FontWeight.SemiBold, FontFamily.Monospace).lineCount)
        val cells = listOf("step-−", "value", "step-＋", "condition").map(::box)
        for ((a, b) in cells.zipWithNext()) {
            assertTrue(a.right <= b.left + 1f)
            assertTrue(a.top < b.bottom && b.top < a.bottom)
        }
    }

    private fun headline(label: String, color: Color = PrimaryText): TextLayoutResult {
        val result = style(label, color, 15, FontWeight.Medium)
        assertEquals(20.sp, result.layoutInput.style.lineHeight)
        assertEquals(1f, result.layoutInput.density.fontScale, 0f)
        assertTrue(box("headline").bottom <= box("left").top + 1f)
        assertEquals(box("headline"), bounds(hasText(label)))
        return result
    }

    private fun expectNoWrite(vararg expected: String) {
        val end = android.os.SystemClock.elapsedRealtime() + 350
        var observed = calls.map { it.method }
        while (observed == expected.toList() && android.os.SystemClock.elapsedRealtime() < end) {
            android.os.SystemClock.sleep(20)
            observed = calls.map { it.method }
        }
        assertEquals(expected.toList(), observed)
    }

    private fun repeatMenu() {
        val labels = listOf("한번만", "1분", "5분", "10분", "30분", "1시간", "2시간", "4시간", "6시간", "12시간", "1일")
        style("반복", PrimaryText, 15, FontWeight.SemiBold)
        for ((current, next) in listOf("한번만" to "5분", "5분" to "한번만")) {
            style(current, Primary, 15, FontWeight.SemiBold)
            assertTrue(bounds(hasText("반복")).right <= box("repeat").left)
            near(box("repeat").right, box("repeat-row").right)
            val withinRow = hasAnyAncestor(hasTestTag("comparison-repeat-row"))
            assertEquals(1, rule.onAllNodes(withinRow and hasClickAction(), true).fetchSemanticsNodes().size)
            val descendants = rule.onAllNodes(hasAnyAncestor(hasTestTag("comparison-repeat")), true).fetchSemanticsNodes()
            assertEquals(1, descendants.size)
            click("comparison-repeat")
            val items = rule.onAllNodes(hasAnyAncestor(isPopup()) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()
            assertEquals(labels, items.map { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text })
            assertEquals(listOf(current), items.filter { it.config[SemanticsProperties.Selected] }.map {
                it.config[SemanticsProperties.Text].single().text
            })
            val target = rule.onAllNodes(hasAnyAncestor(isPopup()) and hasText(next) and hasClickAction()).fetchSemanticsNodes()
            assertEquals(1, target.size)
            act(target.single().config[SemanticsActions.OnClick].action!!)
            editorCount(next, 1)
        }
    }

    @Test fun UI08_fieldsVisualParity() {
        val signed = old.copy(threshold = 0.0, rightSource = "kb")
        start(listOf(signed, absolute))
        val usdt = "usdt-krw"
        fun pairPrices(diff: Double) = rates(Triple("bithumb", usdt, 1400.0 + diff),
            Triple("upbit", usdt, 1400.0), Triple("investing", "usd-krw", 1400.0))
        pairPrices(2.0)
        for (tether in listOf(true, false)) {
            route.select(if (tether) FreeTab.TETHER else FreeTab.USD)
            add("absolute")
            val guidance = if (tether) "비교할 거래소 2곳을 선택해주세요" else "비교할 은행 2곳을 선택해주세요"
            val first = if (tether) "빗썸·업비트 차이가 3원" else "국민·하나 차이가 0.1원"
            val next = if (tether) "업비트·코인원 차이가 1원" else "하나·신한 차이가 0.1원"
            val names = if (tether) listOf("bithumb", "upbit", "coinone") else listOf("kb", "hana", "shinhan")
            headline(guidance)
            pick(names[0]); headline(guidance)
            pick(names[1])
            val summary = "\"$first 이상 벌어지면 알려드려요\""
            headline(summary); editorCount(guidance, 0)
            if (tether) {
                input("3", PrimaryText)
                condition(true)
                repeatMenu()
                click("comparison-step-＋"); input("4", PrimaryText)
                pairPrices(6.0)
                click("comparison-condition"); condition(false); input("7", PrimaryText)
                click("comparison-step-＋"); input("8", PrimaryText)
                pairPrices(2.0)
                click("comparison-condition"); condition(true); input("3", PrimaryText)
            }
            pick(names[2]); headline("\"$next 이상 벌어지면 알려드려요\""); editorCount(summary, 0)
            pick(names[2]); headline(guidance); editorCount("\"$next 이상 벌어지면 알려드려요\"", 0)
            button("취소")
        }
        route.select(FreeTab.TETHER)
        add()
        headline("\"빗썸 김프가 인베스팅 대비 +2원 이상으로 오르면 알려드려요\"")
        click("comparison-step-＋")
        pairPrices(6.0)
        for (above in listOf(false, true)) {
            click("comparison-condition"); condition(above); input("+2.1", StatusOnline)
        }
        pick("bithumb"); input("+6", StatusOnline)
        button("⋮"); button("넓은 범위 설정")
        val badge = "넓은 범위 ±1000"
        val hint = "비정상 급등락 감지용 넓은 범위(±1000)입니다. 시트를 닫으면 기본(±100)으로 돌아갑니다."
        style("김프 기준", SecondaryText, 15, FontWeight.Medium)
        style(badge, Primary, 11, FontWeight.Normal)
        style("⋮", SecondaryText, 18, FontWeight.Normal)
        style(hint, SecondaryText, 12, FontWeight.Normal)
        assertTrue(bounds(hasText("김프 기준")).right <= bounds(hasText(badge)).left)
        assertTrue(bounds(hasText(badge)).right <= box("wide").left)
        near(box("wide").right, box("heading-row").right)
        assertTrue(bounds(hasText(hint)).top >= box("value").bottom)
        button("⋮")
        rule.onNodeWithText("넓은 범위 사용 중 (±1000)").assertIsNotEnabled()
        androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.waitForIdle()
        button("취소")
        for (row in listOf(absolute, signed)) {
            edit(row.id)
            for (above in listOf(false, true)) {
                pairPrices(if (above) 9.0 else 12.0)
                click("comparison-condition"); condition(above)
                input(if (row == signed) "+0" else "4.25", if (row == signed) StatusOnline else PrimaryText)
            }
            button("취소")
        }
        edit(signed.id)
        val normal = "\"빗썸 김프가 국민 대비 +0원 이상으로 오르면 알려드려요\""
        val firstLayout = headline(normal)
        fun slot(result: TextLayoutResult) = Triple(result, box("left").top - box("headline").top, box("headline").height)
        val normalSlot = slot(firstLayout)
        rule.onNodeWithTag("comparison-save", true).assertIsEnabled()
        val saved = kept("comparison-save")
        val duplicate = "이미 등록된 알림이에요. 목록에서 켜서 사용하세요."
        refresh(listOf(signed, signed.copy(id = 91)))
        val duplicateLayout = headline(duplicate, StatusConnecting)
        editorCount(normal, 0)
        val duplicateSlot = slot(duplicateLayout)
        rule.onNodeWithTag("comparison-save", true).assertIsNotEnabled()
        act(saved); expectNoWrite("GET", "GET"); assertTrue(handles.isEmpty())
        refresh(listOf(signed))
        headline(normal); editorCount(duplicate, 0)
        rule.onNodeWithTag("comparison-save", true).assertIsEnabled()
        pick("investing")
        val longer = "\"빗썸 김프가 인베스팅 대비 +0원 이상으로 오르면 알려드려요\""
        val thirdLayout = headline(longer)
        val layouts = listOf(normalSlot, duplicateSlot, slot(thirdLayout))
        assertEquals(1, layouts.map { it.first.layoutInput.constraints.maxWidth }.distinct().size)
        assertEquals(1, layouts.map { it.first.layoutInput.density.density }.distinct().size)
        val one = layouts.firstOrNull { it.first.lineCount == 1 }
        val two = layouts.firstOrNull { it.first.lineCount == 2 }
        android.util.Log.i("UI08", "headline lines=${layouts.map { it.first.lineCount }}")
        if (one == null) {
            android.util.Log.i("UI08", "minLines=2 미측정 lines=${layouts.map { it.first.lineCount }}")
        } else {
            val single = one.first
            assertEquals(1, single.lineCount)
            if (two != null) {
                assertEquals(2, two.first.lineCount)
                assertEquals("UI08 signed headline picker Δy (px)", one.second, two.second, 4f * single.layoutInput.density.density)
            } else {
                assertTrue(one.third + 1 >= 2 * (single.getLineBottom(0) - single.getLineTop(0)))
            }
        }
        val delete = hold("DELETE")
        act(saved)
        record("GET", "GET", "GET", "POST", "DELETE")
        assertEquals(listOf(83, signed.id), ids())
        editorCount(duplicate, 0)
        release(delete, 500)
        style(warning, StatusError, 12, FontWeight.Normal)
        headline(longer); editorCount(duplicate, 0)
        click("comparison-step-＋"); act(saved)
        expectNoWrite("GET", "GET", "GET", "POST", "DELETE")
        assertEquals(1, handles.size)
        button("취소")
        refresh(listOf(signed.copy(operator = "unknown")))
        edit(signed.id)
        rule.onNodeWithTag("comparison-save", true).assertIsNotEnabled()
    }

    @Test fun UI09_kimchiNowAndThreshold() {
        start(emptyList())
        val usdt = "usdt-krw"
        val usd = "usd-krw"
        fun signedRates(left: Double?, right: Double?) {
            val prices = listOfNotNull(left?.let { Triple("bithumb", usdt, it) },
                right?.let { Triple("investing", usd, it) }, Triple("upbit", usdt, 1391.0), Triple("kb", usd, 1399.0))
            rates(*prices.toTypedArray())
        }
        fun caption(leg: String, value: String, expected: Int) = observe(expected) {
            rule.onAllNodes(inEditor and hasAnyAncestor(hasTestTag("comparison-pick-$leg")) and hasText(value), true)
                .fetchSemanticsNodes().size
        }
        fun slotHeight(): Float {
            val nodes = rule.onAllNodes(inEditor and hasTestTag("comparison-current"), true).fetchSemanticsNodes()
            assertTrue(nodes.size <= 1)
            return nodes.singleOrNull()?.size?.height?.toFloat() ?: 0f
        }
        fun numberRow(label: String, amount: String, pct: String?, color: Color) {
            style(label, SecondaryText, 12, FontWeight.Normal)
            style(amount, color, 15, FontWeight.Bold, digits = true)
            val lastPicker = if (pct == null) "left" else "right"
            assertTrue(box(lastPicker).bottom <= box("current").top + 1f)
            assertTrue(box("current").bottom <= box("heading-row").top + 1f)
            assertTrue(box("heading-row").bottom <= box("value").top + 1f)
            assertTrue(box("value").bottom <= box("repeat-row").top + 1f)
            assertTrue(bounds(hasText(label)).right <= bounds(hasText(amount)).left)
            if (pct != null) {
                style(pct, color.copy(alpha = 0.65f), 11, FontWeight.Normal, digits = true)
                val pctBounds = bounds(hasText(pct))
                val amountBounds = bounds(hasText(amount))
                near((pctBounds.left + pctBounds.right) / 2f, (amountBounds.left + amountBounds.right) / 2f)
                assertTrue(pctBounds.top >= amountBounds.bottom)
            }
        }
        fun rig(): Pair<Int, Float> {
            val input = layout("김프 기준").layoutInput
            assertEquals(1f, input.density.fontScale, 0f)
            return box("current").width.toInt() to input.density.density
        }
        signedRates(1400.1, 1400.0)
        add()
        signedRates(1386.0, 1400.0)
        val label = "지금 빗썸-인베스팅"
        numberRow(label, "-14.0원", "-1.00%", StatusError)
        input("+0.1", StatusOnline)
        val fixedRig = rig()
        fun pickerBounds(tag: String): Rect {
            val origin = box("headline").topLeft
            return box(tag).translate(-origin.x, -origin.y)
        }
        val pickers = listOf(pickerBounds("left"), pickerBounds("right"))
        val full = slotHeight()
        assertTrue(full > 0)
        for (leftMissing in listOf(true, false)) {
            signedRates(if (leftMissing) null else 1386.0, if (leftMissing) 1400.0 else null)
            caption("upbit", "1391", 1); caption("kb", "1399.00", 1)
            caption("bithumb", "1386", if (leftMissing) 0 else 1)
            caption("investing", "1400.00", if (leftMissing) 1 else 0)
            for ((a, b) in pickers.zip(listOf(pickerBounds("left"), pickerBounds("right")))) {
                near(a.left, b.left); near(a.right, b.right); near(a.top, b.top); near(a.bottom, b.bottom)
            }
            editorCount(label, 0); editorCount("-14.0원", 0); editorCount("-1.00%", 0)
            near(full, slotHeight())
            assertEquals(fixedRig, rig())
        }
        for ((left, amount, pct) in listOf(Triple(1400.0, "+0.0원", "+0.00%"),
            Triple(1414.0, "+14.0원", "+1.00%"), Triple(1399.99, "-0.0원", "-0.00%"))) {
            signedRates(left, 1400.0)
            numberRow(label, amount, pct, if (left < 1400.0) StatusError else StatusOnline)
            input("+0.1", StatusOnline)
        }
        for ((value, color) in listOf("+0.1" to StatusOnline, "+0" to StatusOnline, "-0.1" to StatusError)) {
            input(value, color)
            click("comparison-condition"); input(value, color)
            click("comparison-condition"); input(value, color)
            click("comparison-step-−")
        }
        button("취소")
        for (tether in listOf(true, false)) {
            route.select(if (tether) FreeTab.TETHER else FreeTab.USD)
            val (a, b, other) = if (tether) listOf("upbit", "bithumb", "coinone") else listOf("kb", "hana", "shinhan")
            val asset = if (tether) usdt else usd
            val first = Triple(a, asset, 1397.0)
            val second = Triple(b, asset, 1400.0)
            val third = Triple(other, asset, 1450.0)
            rates(first, second, third)
            add("absolute")
            val heights = mutableListOf<Float>()
            val gaps = mutableListOf<Float>()
            val heading = "차이 기준"
            val initialLayout = layout(heading).layoutInput
            val width = box("left").width
            assertEquals(1f, initialLayout.density.fontScale, 0f)
            val now = if (tether) "지금 업비트↔빗썸" else "지금 국민↔하나"
            val amount = if (tether) "3원" else "3.0원"
            for ((index, tap) in listOf(null, a, b, a).withIndex()) {
                if (tap != null) pick(tap)
                val height = slotHeight()
                heights += height
                val slotBottom = if (tags("comparison-current").isEmpty()) box("left").bottom else box("current").bottom
                gaps += bounds(hasText(heading)).top - slotBottom
                assertEquals(width, box("left").width, 0f)
                val measuredDensity = layout(heading).layoutInput.density
                assertEquals(initialLayout.density.density, measuredDensity.density, 0f)
                assertEquals(1f, measuredDensity.fontScale, 0f)
                if (index == 2) {
                    assertTrue(height > 0)
                    numberRow(now, amount, null, StatusOnline)
                    input(if (tether) "4" else "3.1", PrimaryText)
                } else {
                    editorCount(now, 0); editorCount(amount, 0)
                }
            }
            near(heights.maxOrNull()!!, heights.minOrNull()!!)
            near(gaps.maxOrNull()!!, gaps.minOrNull()!!)
            pick(a)
            val reversed = if (tether) "지금 빗썸↔업비트" else "지금 하나↔국민"
            numberRow(reversed, amount, null, StatusOnline)
            val picker = pickerBounds("left")
            for (missing in listOf(a, b)) {
                rates(if (missing == a) second else first, third)
                caption(other, if (tether) "1450" else "1450.0", 1)
                caption(a, if (tether) "1397" else "1397.0", if (missing == a) 0 else 1)
                caption(b, if (tether) "1400" else "1400.0", if (missing == b) 0 else 1)
                assertEquals(picker, pickerBounds("left"))
                editorCount(reversed, 0); editorCount(amount, 0)
                near(heights[2], slotHeight())
            }
            button("취소")
        }
    }
}
