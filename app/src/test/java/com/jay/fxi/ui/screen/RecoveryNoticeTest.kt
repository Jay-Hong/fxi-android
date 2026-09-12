package com.jay.fxi.ui.screen

import com.jay.fxi.data.entitlements.HeldWork
import com.jay.fxi.data.entitlements.HoldProgress
import com.jay.fxi.data.entitlements.IdentityRecoveryState
import com.jay.fxi.data.entitlements.NoAutoRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the banner may say, with no coordinator and no composition.
 *
 * These run in the JVM lane, which has junit and coroutines-test but no Compose test runtime; the
 * instrumented sources are compiled and not executed by CI. So this is where the mapping's choices
 * are locked. What follows pins the contracts named in each test — it does not prove how anything
 * renders, nor that wording invented later means what it says.
 */
class RecoveryNoticeTest {

    private fun held(
        id: Long = 7L,
        work: HeldWork = HeldWork.SIGN_IN,
        progress: HoldProgress = HoldProgress.STOPPED,
        reason: NoAutoRetry? = NoAutoRetry.BUDGET_EXHAUSTED
    ) = IdentityRecoveryState.HoldUnfinished(id = id, work = work, progress = progress, reason = reason)

    private val everyWork = HeldWork.entries
    private val everyProgress = HoldProgress.entries
    private val everyReason = listOf(null, NoAutoRetry.BUDGET_EXHAUSTED, NoAutoRetry.UNDECIDABLE)

    /**
     * The agreed copy, written out here rather than read from production.
     *
     * Comparing whole bodies rather than prefixes or substrings is the point: a sentence appended
     * after the right one — "…정리하는 중이었습니다. 데이터는 모두 지워졌습니다." — passed the
     * former `startsWith`/`contains` assertions and the 22-phrase list below, while claiming a
     * deletion the purgers never performed.
     */
    private val workLines = mapOf(
        HeldWork.STARTUP_PURGE to "이전에 사용하던 데이터를 정리하는 중이었습니다.",
        HeldWork.SIGN_IN to "계정 정보를 저장하는 중이었습니다.",
        HeldWork.SIGN_OUT to "계정 사용을 마무리하는 중이었습니다."
    )

    private val progressLines = mapOf(
        HoldProgress.SCHEDULED to "잠시 후 자동으로 다시 시도합니다.",
        HoldProgress.RUNNING to "잠시만 기다려 주세요.",
        HoldProgress.REQUESTED to "다시 확인을 요청했습니다.",
        HoldProgress.STOPPED to "자동 재시도가 멈춰 있습니다."
    )

    private val stopLines = mapOf(
        NoAutoRetry.BUDGET_EXHAUSTED to "자동 재시도를 멈췄습니다.",
        NoAutoRetry.UNDECIDABLE to
            "이 기기에 남은 기록이 이 작업과 맞지 않아 자동 재시도를 멈췄습니다. " +
            "다시 확인해도 같은 결과가 나올 수 있습니다."
    )

    private fun expectedBody(work: String, middle: String) = "$work $middle $ACCESS_HELD_LINE"

    @Test
    fun withNoNoticeSelected_thereIsNoBanner() {
        assertNull(recoveryNoticeFor(IdentityRecoveryState.None))
    }

    /**
     * The normal preparation of an admitted app sign-out already reports this state — the
     * coordinator test `anArmedAttemptIsSurfacedThoughNoRecoveryRunExists` reads it right after
     * preparation returns `Armed`. This pins the slice's choice to hide both `recovering` values;
     * what a running UI would draw during a sign-out is not established here.
     */
    @Test
    fun anOpenSignOutAttemptIsNotABanner() {
        assertNull(
            "열린 로그아웃 시도가 배너로 변환됐다",
            recoveryNoticeFor(IdentityRecoveryState.SignOutUnfinished(recovering = false))
        )
        // Both flags, so this is not read as "only the idle one is hidden".
        assertNull(
            recoveryNoticeFor(IdentityRecoveryState.SignOutUnfinished(recovering = true))
        )
    }

    /** A hold is shown whatever it is doing — access is refused in all four, not only at the stop. */
    @Test
    fun everyHeldProgressIsShown() {
        for (work in everyWork) {
            for (progress in everyProgress) {
                val notice = recoveryNoticeFor(held(work = work, progress = progress))
                assertTrue("$work/$progress 에서 배너가 사라졌다", notice != null)
            }
        }
    }

    /** And the button appears exactly where pressing changes something. */
    @Test
    fun theButtonBelongsToTheStopAlone() {
        for (work in everyWork) {
            for (progress in everyProgress) {
                // The reason axis matters here: `REQUESTED + UNDECIDABLE` is a real state (a wake
                // does not clear `blocked`), and a gate that also fires on the reason would put the
                // button back on a hold the user has already asked about.
                for (reason in everyReason) {
                    val notice = recoveryNoticeFor(held(work = work, progress = progress, reason = reason))!!
                    if (progress == HoldProgress.STOPPED) {
                        assertEquals("$work/$progress/$reason", RECHECK_ACTION, notice.action)
                    } else {
                        assertNull("$work/$progress/$reason 에 버튼이 붙었다", notice.action)
                    }
                }
            }
        }
    }

    /**
     * The shape right after the button is pressed: the wake is recorded and `blocked` is left alone,
     * so a reason survives into REQUESTED. The button must not come back on the strength of it.
     */
    @Test
    fun aRequestedRecheckKeepsItsBannerAndLosesItsButton() {
        val notice = recoveryNoticeFor(
            held(progress = HoldProgress.REQUESTED, reason = NoAutoRetry.BUDGET_EXHAUSTED)
        )!!

        assertNull("요청 중인데 버튼이 다시 떴다", notice.action)
        assertEquals(UNFINISHED_TITLE, notice.title)
    }

    /** A running round is the only state that claims work is under way. */
    @Test
    fun onlyARunningRoundSaysItIsChecking() {
        // Across every work too: the title answers "is a round executing", and nothing about which
        // task was interrupted may reach it.
        for (work in everyWork) {
            for (progress in everyProgress) {
                val title = recoveryNoticeFor(held(work = work, progress = progress, reason = null))!!.title
                if (progress == HoldProgress.RUNNING) {
                    assertEquals("$work/$progress", RUNNING_TITLE, title)
                } else {
                    assertEquals("$work/$progress 가 실행 중이라고 말한다", UNFINISHED_TITLE, title)
                }
            }
        }
    }

    /** Each interrupted task is named, and no two are named the same. */
    @Test
    fun eachHeldWorkIsNamedDistinctly() {
        val bodies = everyWork.map { recoveryNoticeFor(held(work = it))!!.body }
        assertEquals("작업 이름이 뭉개졌다", bodies.size, bodies.toSet().size)
    }

    /**
     * And so is each progress. These are different facts about who acts next — a scheduled batch
     * runs on its own, a requested one is the user's press being carried — so saying "다시 확인을
     * 요청했습니다" to somebody who pressed nothing would be a plain falsehood.
     */
    @Test
    fun eachProgressReadsDifferently() {
        val bodies = everyProgress.map { recoveryNoticeFor(held(progress = it, reason = null))!!.body }
        assertEquals("진행 상태가 뭉개졌다: $bodies", bodies.size, bodies.toSet().size)
    }

    /**
     * Distinctness is not enough — two lines can be exchanged and stay distinct. This pins which
     * line belongs to which task, so a swap is a failure rather than a rename.
     */
    @Test
    fun eachHeldWorkKeepsItsOwnLine() {
        assertEquals("작업 목록이 바뀌었다", everyWork.toSet(), workLines.keys)
        for ((work, line) in workLines) {
            assertEquals(
                "$work",
                expectedBody(line, stopLines.getValue(NoAutoRetry.BUDGET_EXHAUSTED)),
                recoveryNoticeFor(held(work = work))!!.body
            )
        }
    }

    /** Same for progress: a scheduled batch and a carried press must not trade sentences. */
    @Test
    fun eachProgressKeepsItsOwnLine() {
        assertEquals("진행 목록이 바뀌었다", everyProgress.toSet(), progressLines.keys)
        for ((progress, line) in progressLines) {
            assertEquals(
                "$progress",
                expectedBody(workLines.getValue(HeldWork.SIGN_IN), line),
                recoveryNoticeFor(held(progress = progress, reason = null))!!.body
            )
        }
    }

    /**
     * The stop's own two sentences, pinned exactly.
     *
     * `eachProgressKeepsItsOwnLine` reaches STOPPED with no reason, so it leaves both of these
     * unguarded — and a stopped hold that says "잠시 후 자동으로 다시 시도합니다" would be telling
     * the user the opposite of what happened.
     */
    @Test
    fun eachStopReasonKeepsItsOwnLine() {
        assertEquals("멈춤 이유 목록이 바뀌었다", NoAutoRetry.entries.toSet(), stopLines.keys)
        for ((reason, line) in stopLines) {
            assertEquals(
                "$reason",
                expectedBody(workLines.getValue(HeldWork.SIGN_IN), line),
                recoveryNoticeFor(held(reason = reason))!!.body
            )
        }
    }

    /**
     * Every combination, every field, against a table built here.
     *
     * Three mutants survived a battery that looked complete — a gate keyed on the *reason*, a title
     * keyed on the *work*, an id narrowed through `Int`. The gap was not coverage: other tests did
     * reach those combinations. They just never asserted the field the mutant moved, and the id
     * checks used only small values. This one compares all four fields across every combination.
     *
     * It mirrors the mapping's shape rather than calling it, so a change on either side shows up as
     * a disagreement — the table is written out independently and is not what gets mutated.
     */
    @Test
    fun everyCombinationRendersExactly() {
        val id = 4_294_967_296L // past Int, so a narrowed id cannot coincide
        for (work in everyWork) {
            for (progress in everyProgress) {
                for (reason in everyReason) {
                    val middle = if (progress == HoldProgress.STOPPED) {
                        reason?.let { stopLines.getValue(it) } ?: progressLines.getValue(HoldProgress.STOPPED)
                    } else {
                        progressLines.getValue(progress)
                    }
                    val expected = RecoveryNotice(
                        holdId = id,
                        title = if (progress == HoldProgress.RUNNING) RUNNING_TITLE else UNFINISHED_TITLE,
                        body = expectedBody(workLines.getValue(work), middle),
                        action = if (progress == HoldProgress.STOPPED) RECHECK_ACTION else null
                    )
                    assertEquals(
                        "$work/$progress/$reason",
                        expected,
                        recoveryNoticeFor(held(id = id, work = work, progress = progress, reason = reason))
                    )
                }
            }
        }
    }

    /**
     * The copy is the contract. Changing a line has to be an edit here too, so nobody softens the
     * wording into a promise by touching one file.
     */
    @Test
    fun theFixedWordingIsWhatWasAgreed() {
        assertEquals("계정 처리가 아직 완료되지 않았습니다", UNFINISHED_TITLE)
        assertEquals("계정 상태를 다시 확인하고 있습니다", RUNNING_TITLE)
        assertEquals("다시 확인", RECHECK_ACTION)
        assertEquals("확인이 끝날 때까지 구독 상태를 새로 조회하지 않습니다.", ACCESS_HELD_LINE)
    }

    /** The two stops are different facts and must not collapse into one sentence. */
    @Test
    fun theTwoStopReasonsReadDifferently() {
        val exhausted = recoveryNoticeFor(held(reason = NoAutoRetry.BUDGET_EXHAUSTED))!!
        val undecidable = recoveryNoticeFor(held(reason = NoAutoRetry.UNDECIDABLE))!!

        assertNotEquals(exhausted.body, undecidable.body)
        assertTrue(
            "판정 불가에 '같은 결과가 나올 수 있다'는 경고가 없다",
            undecidable.body.contains("같은 결과가 나올 수 있습니다")
        )
        // The caveat is what earns the button here; the action itself is real (it buys a re-read).
        assertEquals(RECHECK_ACTION, undecidable.action)
    }

    /** A stop with no recorded reason still renders. The banner is not the place to crash. */
    @Test
    fun aStopWithNoReasonStillRenders() {
        val notice = recoveryNoticeFor(held(reason = null))!!

        assertEquals(RECHECK_ACTION, notice.action)
        assertTrue(notice.body.isNotBlank())
    }

    /** The one consequence the banner asserts, present in every state it renders. */
    @Test
    fun everyBannerSaysWhatIsHeld() {
        for (work in everyWork) {
            for (progress in everyProgress) {
                for (reason in listOf(null, NoAutoRetry.BUDGET_EXHAUSTED, NoAutoRetry.UNDECIDABLE)) {
                    val body = recoveryNoticeFor(held(work = work, progress = progress, reason = reason))!!.body
                    assertTrue("$work/$progress/$reason", body.contains(ACCESS_HELD_LINE))
                }
            }
        }
    }

    /**
     * Nothing the banner says may promise an outcome.
     *
     * The purgers still answer `Deferred`, so no wording may claim data was removed; and a re-check
     * buys a read, not a completion. "완료되지 않았습니다" is a negation and is allowed — the banned
     * forms are the ones that assert the thing happened or will happen.
     */
    @Test
    fun noWordingPromisesAnOutcome() {
        val banned = listOf(
            "완료됩니다", "완료했습니다", "완료됨", "완료되었습니다", "완료됐습니다",
            "복구됩니다", "복구했습니다", "복구되었습니다", "복구됐습니다",
            "해결됩니다", "해결했습니다", "해결되었습니다", "해결됐습니다",
            "삭제됩니다", "삭제했습니다", "삭제되었습니다", "삭제됐습니다",
            "정리가 끝났습니다", "삭제가 끝났습니다", "정리를 마쳤습니다",
            "정상으로 돌아갑니다", "성공"
        )
        for (work in everyWork) {
            for (progress in everyProgress) {
                for (reason in listOf(null, NoAutoRetry.BUDGET_EXHAUSTED, NoAutoRetry.UNDECIDABLE)) {
                    val notice = recoveryNoticeFor(held(work = work, progress = progress, reason = reason))!!
                    val all = "${notice.title} ${notice.body} ${notice.action.orEmpty()}"
                    for (word in banned) {
                        assertTrue("$work/$progress/$reason 에 '$word'", !all.contains(word))
                    }
                }
            }
        }
    }

    /** The id travels with the notice. A constant here would send re-checks to the wrong hold. */
    @Test
    fun theNoticeCarriesTheStandingHoldsId() {
        // Int boundaries and large Longs both, chosen for where each conversion loses. 2^32 is
        // exact through `Double`, and `Long.MAX_VALUE` returns unchanged because Double-to-Long
        // saturates — so neither shows a `Double` round trip. 2^53 + 1 comes back one lower.
        // (Through `Int` it comes back as 1: that is truncation, not saturation.) A lossy id makes
        // `retryPersistence` refuse the re-check, because the number no longer names the hold.
        val past = Int.MAX_VALUE.toLong()
        for (id in listOf(0L, 1L, 42L, past, past + 1L, 9_007_199_254_740_993L, Long.MAX_VALUE)) {
            assertEquals(id, recoveryNoticeFor(held(id = id))!!.holdId)
        }
    }
}
