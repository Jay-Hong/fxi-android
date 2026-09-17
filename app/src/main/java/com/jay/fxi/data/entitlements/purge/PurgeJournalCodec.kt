package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope

/**
 * One line of the purge journal, as this build was able to read it.
 *
 * The journal is the only record that a namespace is owed a purge, so a line this build cannot read
 * has to survive being read. [Uninterpretable] is what that survival looks like: the text is kept
 * exactly as it was found, and the obligation it stands for is neither performed nor forgotten.
 */
sealed interface JournalEntry {

    /** A line this build understands: who is owed a purge, and why they are (purger 설계 v3 final §3.1). */
    data class Owed(val pending: PendingPurge, val cause: PurgeCause) : JournalEntry

    /**
     * A line this build cannot interpret, kept verbatim.
     *
     * An unknown cause, a malformed new-format line, or one carrying fields this build does not
     * support. It must not be read as the legacy `UNKNOWN` obligation: that would claim the line
     * says something it may not say, and a later build that *can* read it would find the original
     * text gone. The contract is that while one of these stands, the scope it belongs to is not
     * `Completed` and the matching protected entry stays closed, while the entries around it are
     * still worked — **this slice supplies the value, not that enforcement.** Making a purge result
     * and a protected entry answer to it is P3 (purger 설계 v3 final §9).
     */
    data class Uninterpretable(val raw: String) : JournalEntry
}

/**
 * The purge journal's line format, in both directions.
 *
 * **Nothing in production reads or writes through this yet** (purger 설계 v3 final §9, P2a).
 * `DataStoreAccessEpochStore` keeps its own four-field decoder, which turns any line that is not
 * four fields into the widest possible obligation — the very collapse this codec exists to stop.
 * Replacing that decoder is P2's later slice, and switching the writer is P3.
 *
 * The line is `owner|userEpoch|krxEpoch|scopes` with an optional fifth `cause`. Four fields is the
 * shape written before causes existed, and it reads as [PurgeCause.UNKNOWN] — a record of derived
 * data owed a cleanup, and nothing more. §3.1 is explicit that this `UNKNOWN` neither authorises
 * deleting a user's own preferences nor drops an obligation another slice owns.
 */
object PurgeJournalCodec {

    const val FIELD_SEPARATOR = "|"
    const val SCOPE_SEPARATOR = ","
    const val ENTRY_SEPARATOR = "\n"

    /** Everything a purger owns except the live namespace: no owner, no past epoch, both axes. */
    val WIDENED = PendingPurge(
        ownerUid = null,
        userAccessEpoch = null,
        krxCapabilityEpoch = null,
        scopes = PurgeScope.entries.toSet()
    )

    /**
     * Reads one line.
     *
     * Four fields or fewer is the old shape, and a broken one widens exactly as it does today —
     * losing a field there tells us nothing about which namespace is owed, so the obligation
     * becomes every past namespace of both axes. Five is the new shape; a fifth field that is not
     * a cause this build knows, or a scope list it cannot read, is [JournalEntry.Uninterpretable]
     * rather than a guess. More than five fields is a shape from a later build, kept the same way.
     */
    fun decode(raw: String): JournalEntry {
        val parts = raw.split(FIELD_SEPARATOR)
        return when {
            parts.size < 4 -> JournalEntry.Owed(WIDENED, PurgeCause.UNKNOWN)
            parts.size == 4 -> legacy(parts)
            parts.size == 5 -> current(parts, raw)
            else -> JournalEntry.Uninterpretable(raw)
        }
    }

    private fun legacy(parts: List<String>): JournalEntry.Owed {
        val scopes = scopesOf(parts[3])
            ?: return JournalEntry.Owed(WIDENED, PurgeCause.UNKNOWN)
        return JournalEntry.Owed(pendingOf(parts, scopes), PurgeCause.UNKNOWN)
    }

    private fun current(parts: List<String>, raw: String): JournalEntry {
        val cause = PurgeCause.entries.firstOrNull { it.name == parts[4] }
            ?: return JournalEntry.Uninterpretable(raw)
        val scopes = scopesOf(parts[3])
            ?: return JournalEntry.Uninterpretable(raw)
        return JournalEntry.Owed(pendingOf(parts, scopes), cause)
    }

    private fun pendingOf(parts: List<String>, scopes: Set<PurgeScope>) = PendingPurge(
        ownerUid = parts[0].ifEmpty { null },
        userAccessEpoch = parts[1].ifEmpty { null },
        krxCapabilityEpoch = parts[2].ifEmpty { null },
        scopes = scopes
    )

    /**
     * The scope list, or `null` when this build does not recognise all of it.
     *
     * A list only partly understood is not narrowed to the names that were recognised: keeping
     * those would silently drop whatever the unrecognised one stood for. An empty name counts as
     * unrecognised for the same reason — `USER,` is not a USER-only entry.
     *
     * `isNotEmpty()` is implied by the size comparison, because `split` never returns nothing: it
     * is kept so this reads the same as the shipped decoder it will one day replace.
     */
    private fun scopesOf(field: String): Set<PurgeScope>? {
        val names = field.split(SCOPE_SEPARATOR)
        val scopes = names.mapNotNull { name -> PurgeScope.entries.firstOrNull { it.name == name } }.toSet()
        return scopes.takeIf { it.isNotEmpty() && it.size == names.size }
    }

    /**
     * Writes one line.
     *
     * An entry this build read keeps its cause, so it is written in the five-field shape. One it
     * could not read is written back as the text it came in as — re-encoding must not be how an
     * obligation is lost.
     */
    fun encode(entry: JournalEntry): String = when (entry) {
        is JournalEntry.Uninterpretable -> entry.raw
        is JournalEntry.Owed -> {
            val parts = listOf(
                entry.pending.ownerUid.orEmpty(),
                entry.pending.userAccessEpoch.orEmpty(),
                entry.pending.krxCapabilityEpoch.orEmpty(),
                entry.pending.scopes.sortedBy { it.name }.joinToString(SCOPE_SEPARATOR) { it.name },
                entry.cause.name
            )
            require(parts.none { it.contains(FIELD_SEPARATOR) || it.contains(ENTRY_SEPARATOR) }) {
                "purge journal fields must not contain the separators"
            }
            parts.joinToString(FIELD_SEPARATOR)
        }
    }

    /**
     * The whole journal, in order — of a value that **exists**.
     *
     * Absence is the caller's to tell, and it is not the same fact. The writer removes the key when
     * nothing is owed, so a key that is present and says nothing is damage: an empty text is one
     * unreadable line, which widens, and not an empty journal. `DataStoreAccessEpochStore` draws
     * that line with `?.split(...)…orEmpty()` and its own test pins it
     * (`aJournalKeyHoldingNothing_isDamageRatherThanAnEmptyJournal`). Passing an absent key through
     * as `""` instead of treating it as no journal invents a cleanup nobody owed.
     */
    fun decodeAll(raw: String): List<JournalEntry> = raw.split(ENTRY_SEPARATOR).map(::decode)

    /** The whole journal, in order, with every line that could not be read carried through. */
    fun encodeAll(entries: List<JournalEntry>): String =
        entries.joinToString(ENTRY_SEPARATOR, transform = ::encode)
}
