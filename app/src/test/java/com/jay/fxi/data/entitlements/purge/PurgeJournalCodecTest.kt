package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the journal says, and what it must not be made to say.
 *
 * Every assertion here is about an obligation surviving a read: a line written before causes
 * existed must not become permission, and a line this build cannot read must not become either
 * permission or silence.
 */
class PurgeJournalCodecTest {

    private val bothAxes = PurgeScope.entries.toSet()

    /** The shape written before causes existed reads as UNKNOWN — a cleanup owed, and nothing more. */
    @Test
    fun `a four field entry reads as the unknown cause`() {
        val entry = PurgeJournalCodec.decode("u1|old-user||USER")

        assertEquals(
            JournalEntry.Owed(
                PendingPurge("u1", "old-user", null, setOf(PurgeScope.USER)),
                PurgeCause.UNKNOWN
            ),
            entry
        )
    }

    /** …and that UNKNOWN is not an account deletion, so it never reaches the user's own preferences. */
    @Test
    fun `the unknown cause of a legacy entry authorises nothing about preferences`() {
        val legacy = PurgeJournalCodec.decode("u1|old-user||USER") as JournalEntry.Owed
        val preferences = PurgeManifest.byId("datastore:fxi_user_intent")!!

        assertEquals(
            "원인 없는 옛 항목이 선호 삭제 권한을 만들었다",
            TargetDisposition.NOT_APPLICABLE,
            PurgeDecision.disposition(
                preferences,
                PurgeScope.USER,
                legacy.cause,
                com.jay.fxi.data.entitlements.PurgeNamespace("u1", "live", null, legacy.pending),
                DeletionAuthorization("u1", "op-1", localCleanupAllowed = true)
            )
        )
    }

    /** A cause this build knows is kept as written. */
    @Test
    fun `a five field entry keeps its cause`() {
        PurgeCause.entries.forEach { cause ->
            val entry = PurgeJournalCodec.decode("u1|old-user|old-krx|CAPABILITY,USER|${cause.name}")

            assertEquals(
                "cause=$cause",
                JournalEntry.Owed(PendingPurge("u1", "old-user", "old-krx", bothAxes), cause),
                entry
            )
        }
    }

    /**
     * A broken line of the old shape widens, exactly as the shipped decoder does today.
     *
     * Losing a field there says nothing about which namespace is owed, so the obligation becomes
     * every past namespace of both axes — wider, never narrower.
     */
    @Test
    fun `a broken legacy entry widens to both axes with no owner`() {
        listOf("u1|old-user", "", "u1|old-user||NOPE", "u1|old-user||USER,").forEach { raw ->
            assertEquals(
                "raw=$raw",
                JournalEntry.Owed(PurgeJournalCodec.WIDENED, PurgeCause.UNKNOWN),
                PurgeJournalCodec.decode(raw)
            )
        }
        assertEquals(bothAxes, PurgeJournalCodec.WIDENED.scopes)
        assertEquals(null, PurgeJournalCodec.WIDENED.ownerUid)
    }

    /**
     * A new-shape line this build cannot read is kept verbatim, not folded into the legacy UNKNOWN.
     *
     * Folding it would claim the line says something it may not say, and would destroy the text a
     * later build could read. An unknown cause, an unreadable scope list beside a known cause, and
     * a shape carrying more fields than this build supports all land here.
     */
    @Test
    fun `an unreadable new shape entry is kept verbatim`() {
        listOf(
            "u1|old-user||USER|SOMETHING_ELSE",
            "u1|old-user||NOPE|SIGN_OUT",
            "u1|old-user||USER|SIGN_OUT|extra"
        ).forEach { raw ->
            assertEquals("raw=$raw", JournalEntry.Uninterpretable(raw), PurgeJournalCodec.decode(raw))
        }
    }

    /** Case matters: a cause is the name this build writes, not something close to it. */
    @Test
    fun `a cause that only resembles a known one is not read as it`() {
        listOf("sign_out", "SIGN_OUT ", " SIGN_OUT", "SIGNOUT").forEach { name ->
            val raw = "u1|old-user||USER|$name"
            assertTrue(
                "닮은 이름을 원인으로 읽었다: $name",
                PurgeJournalCodec.decode(raw) is JournalEntry.Uninterpretable
            )
        }
    }

    /**
     * A line of the old shape that lost a field still widens — it does not become unreadable.
     *
     * Three fields is damage to a four-field line, and damage there says nothing about which
     * namespace is owed. Calling it uninterpretable instead would block the scope's completion for
     * good over a line whose only problem is that it is old and broken.
     */
    @Test
    fun `a legacy line short of a field widens rather than becoming unreadable`() {
        listOf("u1|old-user|old-krx", "||", "u1|old-user").forEach { raw ->
            assertEquals(
                "raw=$raw",
                JournalEntry.Owed(PurgeJournalCodec.WIDENED, PurgeCause.UNKNOWN),
                PurgeJournalCodec.decode(raw)
            )
        }
    }

    /**
     * An empty field is an unknown, not an empty string — and the difference decides a refusal.
     *
     * `PurgeDecision.authorizes` and `OwnerStampedPreferencesAdapter` both read the entry's owner
     * with `?:`, so an owner decoded as `""` would slip past the "could not be narrowed" branch and
     * be compared as a real uid. The adapter would answer `NothingToRemove` where it must refuse.
     */
    @Test
    fun `empty fields decode to unknown rather than to empty text`() {
        val entry = PurgeJournalCodec.decode("|||USER|SIGN_OUT") as JournalEntry.Owed

        assertEquals(null, entry.pending.ownerUid)
        assertEquals(null, entry.pending.userAccessEpoch)
        assertEquals(null, entry.pending.krxCapabilityEpoch)
        assertEquals(setOf(PurgeScope.USER), entry.pending.scopes)

        // The refusal that an owner of "" would have skipped.
        assertEquals(
            "owner 를 좁힐 수 없는 항목이 계정삭제 권한을 얻었다",
            TargetDisposition.OUTSTANDING,
            PurgeDecision.disposition(
                PurgeManifest.byId("datastore:fxi_user_intent")!!,
                PurgeScope.USER,
                PurgeCause.ACCOUNT_DELETION,
                com.jay.fxi.data.entitlements.PurgeNamespace("u1", "live", null, entry.pending),
                DeletionAuthorization("u1", "op-1", localCleanupAllowed = true)
            )
        )
    }

    /** Writing what was read gives back what was there — for both kinds of line. */
    @Test
    fun `every entry round-trips`() {
        listOf(
            "u1|old-user||USER|SIGN_OUT",
            "|old-user|old-krx|CAPABILITY,USER|ACCOUNT_DELETION",
            "u1|old-user||USER|SOMETHING_ELSE",
            "u1|old-user||USER|SIGN_OUT|extra"
        ).forEach { raw ->
            assertEquals("raw=$raw", raw, PurgeJournalCodec.encode(PurgeJournalCodec.decode(raw)))
        }
    }

    /** A legacy line is rewritten in the current shape, and says the same thing it said. */
    @Test
    fun `a legacy entry is rewritten with its cause spelled out`() {
        val rewritten = PurgeJournalCodec.encode(PurgeJournalCodec.decode("u1|old-user||USER"))

        assertEquals("u1|old-user||USER|UNKNOWN", rewritten)
        assertEquals(PurgeJournalCodec.decode("u1|old-user||USER"), PurgeJournalCodec.decode(rewritten))
    }

    /**
     * A line nobody could read does not take the others down with it.
     *
     * The journal is worked entry by entry, so one unreadable line blocks its own obligation and
     * leaves the rest to be done — while still being there when the journal is written back.
     */
    @Test
    fun `an unreadable line neither hides nor is hidden by the readable ones`() {
        val journal = listOf(
            "u1|old-user||USER|SIGN_OUT",
            "u2|other||USER|WHAT_IS_THIS",
            "u3|third||CAPABILITY"
        ).joinToString("\n")

        val entries = PurgeJournalCodec.decodeAll(journal)

        assertEquals(3, entries.size)
        assertEquals(PurgeCause.SIGN_OUT, (entries[0] as JournalEntry.Owed).cause)
        assertEquals(JournalEntry.Uninterpretable("u2|other||USER|WHAT_IS_THIS"), entries[1])
        assertEquals(PurgeCause.UNKNOWN, (entries[2] as JournalEntry.Owed).cause)
        assertEquals(
            "다시 쓰면서 읽지 못한 줄을 잃었다",
            "u1|old-user||USER|SIGN_OUT\nu2|other||USER|WHAT_IS_THIS\nu3|third||CAPABILITY|UNKNOWN",
            PurgeJournalCodec.encodeAll(entries)
        )
    }

    /**
     * A stored value that says nothing is damage, not an empty journal — the same line the store draws.
     *
     * The writer removes the key when nothing is owed, so the only "nothing owed" is an absent key,
     * which never reaches this codec. `DataStoreAccessEpochStoreTest`'s
     * `aJournalKeyHoldingNothing_isDamageRatherThanAnEmptyJournal` pins the shipped behaviour, and
     * these are the same cases read through the new codec: every one of them keeps an obligation.
     */
    @Test
    fun `a stored value saying nothing keeps an obligation`() {
        val widened = JournalEntry.Owed(PurgeJournalCodec.WIDENED, PurgeCause.UNKNOWN)
        val cases = mapOf(
            "빈 문자열" to ("" to listOf(widened)),
            "줄바꿈만" to ("\n" to listOf(widened, widened)),
            "정상 항목과 빈 줄" to ("u0|e1|e2|USER\n" to listOf(
                JournalEntry.Owed(
                    PendingPurge("u0", "e1", "e2", setOf(PurgeScope.USER)),
                    PurgeCause.UNKNOWN
                ),
                widened
            )),
            "빈 scope 이름 혼합" to ("u0|e1|e2|USER," to listOf(widened))
        )

        cases.forEach { (name, case) ->
            val (raw, expected) = case
            assertEquals("$name: 손상된 기록을 할 일 없음으로 읽었다", expected, PurgeJournalCodec.decodeAll(raw))
        }
    }

    /** Nothing owed is an empty list, and writing it gives back the empty text the caller removes the key for. */
    @Test
    fun `an empty list of entries writes nothing`() {
        assertEquals("", PurgeJournalCodec.encodeAll(emptyList()))
    }

    /** A value carrying a separator would split into a different entry, so writing it is refused. */
    @Test
    fun `a field containing a separator is refused rather than written`() {
        listOf("u1|evil", "u1\nevil").forEach { owner ->
            val entry = JournalEntry.Owed(
                PendingPurge(owner, "old-user", null, setOf(PurgeScope.USER)),
                PurgeCause.SIGN_OUT
            )
            val thrown = runCatching { PurgeJournalCodec.encode(entry) }.exceptionOrNull()

            assertTrue("구분자가 든 값을 그대로 썼다: $owner", thrown is IllegalArgumentException)
        }
    }

    /**
     * Nothing in the app reaches this codec yet — the slice boundary, checked rather than asserted.
     *
     * P2a supplies the reader and leaves `DataStoreAccessEpochStore` on its own four-field decoder,
     * which still turns a five-field line into the widest obligation. The day that changes is a
     * later slice, and this test is what notices if it changes by accident.
     */
    @Test
    fun `no production code references this codec yet`() {
        val sources = File("src/main/java/com/jay/fxi").walkTopDown().filter { it.extension == "kt" }
        val referencing = sources
            .filter { it.name != "PurgeJournalCodec.kt" && it.readText().contains("PurgeJournalCodec") }
            .map { it.name }
            .toList()

        assertEquals("배선되지 않아야 할 codec 을 운영 코드가 부른다", emptyList<String>(), referencing)

        // Positive control: the walk must actually see the shipped decoder, or an empty result above
        // would let this pass while reading nothing.
        val shipped = sources.firstOrNull { it.name == "DataStoreAccessEpochStore.kt" }?.readText()
        assertTrue("훑기가 운영 decoder 를 못 찾았다", shipped != null)
        assertTrue(
            "운영 decoder 가 더 이상 네 필드에서 넓히지 않는다 — 경계가 바뀌었다",
            shipped!!.contains("if (parts.size != 4) return UNKNOWN_OBLIGATION")
        )
    }
}
