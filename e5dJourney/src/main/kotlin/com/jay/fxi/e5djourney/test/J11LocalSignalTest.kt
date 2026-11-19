package com.jay.fxi.e5djourney.test

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.PAYWALL_TEXT
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.disposes
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.liveMount
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.after
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.texts
import java.util.Locale
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5d-J J2b2 G11 (`e5dj_j2_plan.claude.r3.md` §3 G11 with R10 and §C2; `s7e5dj_j2b2_design.codex.r1.md` D5): the local
 * premium signal in two steps, then the premium editor and one Save.
 *
 * Before the journey, outside its baseline (plan §2.1 R8): the owner's premium last bank is prepared as KB once through the
 * production store and read back, in a process that is then ended; the measured process starts after it and publishes the
 * fixture identity at its start.
 *
 * The preview is edited to 하나은행, 이상, an own threshold and a 5-minute repeat, then its CTA opens the paywall. Step 1: with the
 * same owner's FreeConfirmed as baseline (decision generation, last user end, no query in flight, a stable reading of the
 * production coordinator), its identity binding — checked against the owned owner and generation — and its binding epoch are
 * kept apart, with the free 달러 page's mount and host. The fixture's local signal — the production entry a purchase success
 * calls — starts the existing fresh requery, the server answers free, and the journey waits for that answer's processing: a
 * larger decision generation, a new AUTHORITATIVE_LOSS end, the snapshot's and the end's binding both the baseline binding, the
 * baseline epoch, FreeConfirmed under the same owner and generation, no query in flight, all in one stable reading; in the
 * record between the signal and that reading, exactly one entitlements answer (fresh, free) and no auth boundary. The same free
 * host and mount and the paywall are still there. Step 2, only then: the answer turns active, and the same probe's next normal
 * query makes PremiumConfirmed for the same owner, generation, binding and epoch, with no auth boundary since the signal and the
 * one signal only — the free host goes, the paywall closes. A probe that ends first is a failure.
 *
 * The premium editor, opened by the user: 국민은행, 이하, 한번만 and the premium KB quote, and none of the preview's values. Another
 * bank (신한은행) saved: the server's applied row (owner, bank, condition, threshold, repeat) and the row on screen; the next Add
 * starts from 신한은행. The final count, on one fixed upper: no alert-family write from the measured process's start to the save
 * mark, and from it exactly that one POST and no other. The purchase and restore success handling is not run here; it is bound
 * statically.
 */
@RunWith(AndroidJUnit4::class)
class J11LocalSignalTest {
    private val run = E5dJourneyRun()
    private val preview = E5dJourneyPreview(run)

    @Before
    fun ready() = preview.ready()

    /** The premium session this case reaches is ended: the next case starts from a new process. */
    @After
    fun close() {
        runCatching { preview.clear() }
        runCatching { preview.endProcess() }
        run.close()
    }

    @Test
    fun J11_0_aLocalSignalIsProcessedFree_thenTheSameProbeConfirmsPremium_andTheEditorSavesOnce() {
        // Before the case's journey and outside its baseline (plan r3 §2.1 R8; J2b2 r1 R5): the owner's premium last bank is
        // prepared as KB once, through the production store's own write, and read back through its own read, in a process that
        // is then ended and collected. The measured process starts after it, and the fixture identity is published there, at
        // its start. A restart is not assumed to reset the bank: what the editor starts from is this preparation's.
        val prepared = run.discover().getString("nonce")
        val seed = run.client.ack("seed-last-bank", prepared) { putExtra("arg", "kb") }
        Log.i(TAG, "seed $seed")
        assertEquals("premise: KB 준비의 read-back", "kb", seed.optString("readBack"))
        run.collect(prepared, null)
        preview.restartOn(other = "유로", tab = "달러")
        run.run { nonce ->
            assertNotEquals("측정 process 는 준비 process 가 아니다", prepared, nonce)
            val measure = preview.phase(nonce, "measure")
            run.awaitRecord(nonce, "startup", timeoutMillis = 60_000) { lines -> texts(lines, "http").any { "-> entitlements free" in it } }

            // The preview's own values, then the paywall.
            preview.open()
            val initial = preview.initialThreshold()
            preview.edit()
            val own = String.format(Locale.US, "%.2f", initial.toDouble() + OWN_OFFSET)
            preview.inSheet(By.clazz("android.widget.EditText"), "임계값").setText(own)
            run.device.waitForIdle()
            preview.inSheet(By.text("한번만"), "한번만").click()
            popupItem("5분").click()
            preview.inSheet(By.text("5분"), "5분")
            preview.inSheet(By.text(E5dJourneyPreview.CTA_TEXT), E5dJourneyPreview.CTA_TEXT).click()
            preview.sheetGone()
            run.waitText(PAYWALL_TEXT)

            // Step 1: the baseline — each axis kept apart — the signal with the free answer, and its processing.
            val base = access(nonce)
            val owner = base.getJSONObject("ownedAfter")
            assertEquals("baseline FreeConfirmed", "FreeConfirmed", owner.getString("state"))
            val baseSnapshot = base.getJSONObject("snapshotAfter")
            val binding = baseSnapshot.optJSONObject("binding")
            assertTrue("baseline 의 identity binding 이 owned 와 같은 owner·세대다 $binding", binding != null && sameOwner(binding, owner))
            val epoch = base.getJSONObject("diagnostics").getLong("bindingEpoch")
            val generation = baseSnapshot.getLong("decisionGeneration")
            val endBefore = baseSnapshot.optJSONObject("lastUserEnd")?.getLong("sequence") ?: 0L
            assertEquals("baseline 에 진행 중 조회가 없다", 0, base.getJSONObject("diagnostics").getJSONArray("inFlightOrders").length())
            // The free host and its page mount: the 달러 page's live mount, and the host set the fixture reports for it.
            val freeMount = liveMount(run.client.dump(nonce).lines, "USD") ?: error("premise: free 달러 page 의 mount 가 기록에 없다")
            assertTrue("premise: free host 가 붙어 있다 $freeMount", hostAttached(nonce, freeMount))
            Log.i(TAG, "baseline binding=$binding epoch=$epoch generation=$generation end=$endBefore free=$freeMount")
            val signal = preview.phase(nonce, "signal-free")
            run.client.ack("local-premium", nonce)
            val processed = awaitAccess(nonce, "free 응답의 처리", timeoutMillis = 30_000, pollMillis = 100) { a ->
                val snapshot = a.getJSONObject("snapshotAfter")
                val end = snapshot.optJSONObject("lastUserEnd")
                val owned = a.getJSONObject("ownedAfter")
                snapshot.getLong("decisionGeneration") > generation && end != null && end.getLong("sequence") > endBefore &&
                    end.getString("reason") == "AUTHORITATIVE_LOSS" && sameBinding(end.optJSONObject("binding"), binding) &&
                    sameBinding(snapshot.optJSONObject("binding"), binding) &&
                    a.getJSONObject("diagnostics").getLong("bindingEpoch") == epoch &&
                    owned.getString("state") == "FreeConfirmed" && owned.optString("uid") == owner.optString("uid") &&
                    owned.optLong("authGeneration", -1) == owner.getLong("authGeneration") &&
                    a.getJSONObject("diagnostics").getJSONArray("inFlightOrders").length() == 0
            }
            // Which answer the decision came from: exactly one entitlements answer between the signal and the record's upper just
            // before the reading, and none received while the reading was taken; no auth boundary in all of it.
            val all = run.client.dump(nonce).lines
            val upperBefore = processed.getInt("recordUpperBefore")
            val upperAfter = processed.getInt("recordUpperAfter")
            Log.i(TAG, "processed: signal=$signal upperBefore=$upperBefore upperAfter=$upperAfter")
            val between = all.drop(signal).take(upperBefore - signal)
            val during = all.drop(upperBefore).take(upperAfter - upperBefore)
            val answers = texts(between, "http").filter { "/api/entitlements " in it }
            assertEquals("신호 뒤 관측 전 entitlements 응답은 하나: $answers", 1, answers.size)
            assertTrue("fresh_premium=true 의 free 응답 ${answers.single()}", answers.single().matches(Regex("""GET /api/entitlements q=fresh_premium=true -> entitlements free rev=0""")))
            assertTrue("관측 중 들어온 응답이 없다", texts(during, "http").none { "/api/entitlements " in it })
            assertTrue("auth 경계가 없다", texts(all.drop(signal).take(upperAfter - signal), "auth").isEmpty())
            assertTrue("premise: 처리 관측이 안정됐다 ${processed.getBoolean("stable")}", processed.getBoolean("stable"))
            // Only once the same free host and its mount are still there, and the paywall too, does the answer turn active.
            val sinceSignal = run.client.dump(nonce).lines.drop(signal)
            assertTrue("free mount 가 그대로다 ${texts(sinceSignal, "hook")}", freeMount !in disposes(sinceSignal) &&
                texts(sinceSignal, "hook").none { it == "set ${freeMount.set} context none" || it == "set ${freeMount.set} detached" })
            assertEquals("같은 free mount", freeMount, liveMount(run.client.dump(nonce).lines, "USD"))
            assertTrue("같은 free host 가 붙어 있다", hostAttached(nonce, freeMount))
            assertTrue("free 처리 뒤에도 paywall 이 있다", preview.paywallShown())

            // Step 2: the answer turns active; the same probe's next query confirms premium, for the same owner, generation,
            // identity binding and binding epoch, with no auth boundary since the signal and the one signal only.
            val active = preview.phase(nonce, "signal-active")
            run.client.ack("answer", nonce) { putExtra("arg", "active") }
            val confirmed = awaitAccess(nonce, "PremiumConfirmed", timeoutMillis = PROBE_MILLIS) { a -> a.getJSONObject("ownedAfter").getString("state") == "PremiumConfirmed" }
            val premiumOwned = confirmed.getJSONObject("ownedAfter")
            assertEquals("같은 owner", owner.getString("uid"), premiumOwned.getString("uid"))
            assertEquals("같은 세대", owner.getLong("authGeneration"), premiumOwned.getLong("authGeneration"))
            assertTrue("같은 identity binding ${confirmed.getJSONObject("snapshotAfter").optJSONObject("binding")}",
                sameBinding(confirmed.getJSONObject("snapshotAfter").optJSONObject("binding"), binding))
            assertEquals("같은 binding epoch", epoch, confirmed.getJSONObject("diagnostics").getLong("bindingEpoch"))
            val confirmedUpper = confirmed.getInt("recordUpperAfter")
            val toConfirmed = run.client.dump(nonce).lines.take(confirmedUpper)
            assertTrue("신호부터 확정 관측까지 auth 경계가 없다", texts(toConfirmed.drop(signal), "auth").isEmpty())
            assertEquals("신호는 한 번뿐이다", 1, texts(toConfirmed, "control").count { it == "local-premium signal" })
            waitPremiumRoot()
            assertFalse("paywall 이 닫혔다", preview.paywallShown())
            val afterActive = texts(after(run.client.dump(nonce).lines, active), "http").filter { "/api/entitlements " in it }
            assertTrue("active 응답으로 확정됐다 $afterActive", afterActive.isNotEmpty() && afterActive.all { it.endsWith("-> entitlements active rev=1") })

            // The premium editor: opened by the user, from its own initial values.
            preview.phase(nonce, "premium-editor")
            run.reveal(ADD_BUTTON).click()
            val sheet = editorSheet()
            inEditor(sheet, By.text("국민은행"), "국민은행")
            val threshold = inEditor(sheet, By.clazz("android.widget.EditText"), "임계값").text.orEmpty()
            val quote = inEditor(sheet, By.textStartsWith("현재 국민은행 환율: "), "KB 시세").text.orEmpty()
            assertEquals("premium KB 시세로 시작한다", quote.removePrefix("현재 국민은행 환율: ").removeSuffix("원"), threshold)
            inEditor(sheet, By.text("이하"), "이하")
            inEditor(sheet, By.text("한번만"), "한번만")
            assertNotEquals("preview 의 임계값이 아니다", own, threshold)
            assertTrue("preview 의 은행·방향·반복이 없다", editorSheetOrNull()?.let { s ->
                s.findObjects(By.text("하나은행")).isEmpty() && s.findObjects(By.text("이상")).isEmpty() && s.findObjects(By.text("5분")).isEmpty()
            } == true)

            // Another bank, saved.
            inEditor(sheet, By.desc("신한은행"), "신한은행").click()
            run.device.waitForIdle()
            inEditor(sheet, By.text("신한은행"), "신한은행")
            val saved = inEditor(sheet, By.clazz("android.widget.EditText"), "임계값").text.orEmpty()
            val save = preview.phase(nonce, "save")
            inEditor(sheet, By.text("저장"), "저장").click()
            awaitEditorGone()
            val lines = run.awaitRecord(nonce, "저장", timeoutMillis = 20_000) { lines -> texts(after(lines, save), "http").any { it.startsWith("POST /api/notification-settings") } }
            val post = E5dJourneyTraffic.server(lines, save).first { it.stream == "http" && it.text.startsWith("POST /api/notification-settings ") }
            assertTrue("서버에 적용됐다 ${post.text}", post.text.matches(Regex(
                """POST /api/notification-settings q= -> alerts create id=(\d+) owner=u1 bank="shinhan" currency="usd-krw" condition="below" """ +
                    """threshold=${Regex.escape(saved.toDouble().toString())} enabled=true repeat=(null|omitted) n=\d+""")))
            val id = Regex("""id=(\d+)""").find(post.text)!!.groupValues[1]
            run.reveal("bank-alert-row-$id")

            // The next Add starts from the bank just saved.
            run.reveal(ADD_BUTTON).click()
            val next = editorSheet()
            inEditor(next, By.text("신한은행"), "신한은행")
            inEditor(next, By.text("취소"), "취소").click()
            awaitEditorGone()

            // The final count (J2b2 r1 R4), on one fixed upper: no alert-family write from the measured process's start — after
            // the preparation — to the save mark; from the save mark to that upper exactly one alert POST, the one the row and the
            // next Add were bound to, and no other alert-family write. Device registration is counted on its own path.
            run.settle(nonce)
            val record = run.client.dump(nonce)
            val server = E5dJourneyTraffic.server(record.lines, 0, record.upper)
            val writes = server.filter { it.stream == "http" && ALERT_WRITE.containsMatchIn(it.text) }
            Log.i(TAG, "final count: measure=$measure save=$save upper=${record.upper} alert writes ${writes.map { "${it.sequence} ${it.text}" }}")
            assertEquals("측정 시작부터 save mark 까지 alert 저장 요청이 없다", emptyList<String>(), writes.filter { it.sequence <= save }.map { it.text })
            val afterSave = writes.filter { it.sequence > save }
            assertEquals("save mark 부터 최종 상한까지 alert 저장 요청은 그 POST 하나", listOf(post.sequence), afterSave.map { it.sequence })
            val registrations = E5dJourneyTraffic.count(server, "http POST /api/register-device .*")
            Log.i(TAG, "final count: register-device $registrations")
            assertTrue("register-device 는 많아야 한 번 ($registrations)", registrations <= 1)
            assertEquals("신호는 한 번뿐이다(최종)", 1, texts(record.lines, "control").count { it == "local-premium signal" })
        }
    }

    // Steps ----------------------------------------------------------------------------------------------------------------

    private fun access(nonce: String): JSONObject = awaitAccess(nonce, "안정된 관측", timeoutMillis = 10_000) { true }

    /** A stable reading of the production access coordinator that satisfies [test]; unstable readings are not evidence. */
    private fun awaitAccess(nonce: String, what: String, timeoutMillis: Long, pollMillis: Long = 250, test: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last: JSONObject? = null
        while (true) {
            val reading = run.client.ack("access", nonce)
            last = reading
            if (reading.getBoolean("stable") && test(reading)) return reading.also { Log.i(TAG, "access $what $it") }
            check(System.currentTimeMillis() < deadline) { "$what 이(가) 관측되지 않았다: $last" }
            Thread.sleep(pollMillis)
        }
    }

    private fun sameOwner(binding: JSONObject?, owner: JSONObject): Boolean =
        binding != null && binding.optString("uid") == owner.optString("uid") && binding.optLong("authGeneration", -2) == owner.getLong("authGeneration")

    /** Two identity bindings naming the same owner and generation; an absent one is never the same. */
    private fun sameBinding(a: JSONObject?, b: JSONObject?): Boolean =
        a != null && b != null && a.optString("uid") == b.optString("uid") && a.optLong("authGeneration", -2) == b.optLong("authGeneration", -3)

    /** Whether the fixture reports [mount]'s hook set attached, for the same host. */
    private fun hostAttached(nonce: String, mount: E5dJourneyPreview.Mount): Boolean {
        val sets = run.client.ack("hosts", nonce).getJSONArray("sets")
        return (0 until sets.length()).map { sets.getJSONObject(it) }.any {
            it.optLong("set", -1).toString() == mount.set && it.optBoolean("attached") && it.optLong("host", -1).toString() == mount.host
        }
    }

    private fun waitPremiumRoot() {
        val deadline = System.currentTimeMillis() + 30_000
        while (run.onScreen(By.res(PREMIUM_MARKER)).isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "premium Root 가 오지 않았다. 화면: ${run.describeScreen()}" }
            Thread.sleep(250)
        }
    }

    /** A dropdown item: the popup is a window of its own, above the sheet. */
    private fun popupItem(text: String): UiObject2 {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val found = run.onScreen(By.text(text))
            if (found.size == 1) return found.single()
            check(System.currentTimeMillis() < deadline) { "'$text' 메뉴 항목이 ${found.size}개" }
            Thread.sleep(200)
        }
    }

    /** The premium editor's window root, found by a text only that sheet has (its title is shared with the button). */
    private fun editorSheetOrNull(): UiObject2? = try {
        var node = run.onScreen(By.text(EDITOR_MARKER)).singleOrNull()
        while (node != null) node = node.parent ?: break
        node
    } catch (stale: StaleObjectException) {
        null
    }

    private fun editorSheet(): UiObject2 {
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            editorSheetOrNull()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "알림 편집 시트가 오지 않았다" }
            Thread.sleep(200)
        }
    }

    private fun awaitEditorGone() {
        val deadline = System.currentTimeMillis() + 20_000
        while (editorSheetOrNull() != null) {
            check(System.currentTimeMillis() < deadline) { "알림 편집 시트가 닫히지 않았다. 화면: ${run.describeScreen()}" }
            Thread.sleep(200)
        }
    }

    /** One node inside the premium editor's window, scrolled to downwards inside it only. */
    @Suppress("UNUSED_PARAMETER")
    private fun inEditor(sheet: UiObject2, selector: BySelector, what: String): UiObject2 =
        run.reveal(selector, what, Direction.DOWN, bothWays = false, scope = { editorSheet() })

    private companion object {
        const val TAG = "E5dJourneyJ11"
        /** An alert-family write as the server records it: any write method on a bank, source or comparison alert path. */
        val ALERT_WRITE = Regex("""^(POST|PUT|DELETE|PATCH) /api/(notification-settings|source-notification-settings|comparison-alerts)(/|\s)""")
        const val ADD_BUTTON = "bank-alert-add-button"
        const val EDITOR_MARKER = "알림 받을 환율"
        const val PREMIUM_MARKER = "premium_settings"
        const val OWN_OFFSET = 3.0
        /** The probe's ticks after the first: 2, 5, 10 and 20 s, each ±20 %, plus each query's own time. */
        const val PROBE_MILLIS = 60_000L
    }
}
