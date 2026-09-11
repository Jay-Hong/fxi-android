package com.jay.fxi.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every DataStore this app keeps, and whether it is meant to cross devices.
 *
 * D27 splits stores in two: user intent travels, control-plane provenance must not. Until now that
 * split lived only in two XML comments, and nothing failed when a new store arrived — a store is
 * backed up unless excluded, so forgetting to think about one is silent and looks like a decision.
 *
 * This is the ledger. A new store fails it until it is listed here with an answer, and the answer
 * is checked against what the XMLs actually say. Writing it turned up three stores nobody had
 * decided about, one of which the plan had already ruled on.
 */
class BackupRulesLedgerTest {

    /**
     * The stores, and why each one does or does not travel.
     *
     * `true` — user intent: what someone chose, worth carrying to a new phone and harmless there.
     * `false` — must not travel.
     */
    private val expected = mapOf(
        // v2, UID-scoped: each file names its owner and refuses to answer anyone else.
        "fxi_user_intent" to true,       // rate row order and hidden set
        "fxi_free_graph" to true,        // which graph series the free surface draws
        "fxi_free_tab" to true,          // the tab to reopen on

        // Control-plane provenance. An epoch restored onto a different install points cleanup at a
        // namespace that never existed there.
        "fxi_access_epoch" to false,

        // Which `(uid, token)` registrations the server may hold, and which are owed a DELETE. An entry
        // restored onto another install names that device's token; resuming it would DELETE the other
        // device's registration.
        "fxi_push_registration_ledger" to false,

        // v1, with **no UID at all**. `ANDROID_V2_PLAN.md §7 S1.5` requires that a backup restore
        // not attribute v1 bank preferences to another UID, and a file that never recorded one
        // cannot help doing exactly that. Excluded here, and deleted outright on start — see
        // `RetiredStores`, and `theRetiredStoresAreAlsoKeptOutOfBackup` below for why both.
        "fxi_bank_preferences" to false,

        // v1, no UID either, and the same shape of problem — but `ANDROID_V2_PLAN.md §9.1` already
        // hands it to **S4**, with the Graph V2 cutover. Recorded rather than changed here: a slice
        // that was not asked to own it should not quietly decide it.
        "fxi_graph_preferences" to true,

        // The legacy paid surface's cache, reached only through `MainScreen`, which has no caller.
        // Two slices share it: `ANDROID_V2_PLAN.md` §9.1 gives `rates`/`rates_timestamp` to **S3**
        // and `last_bank_*` to **S7**. Neither is this slice.
        "fxi_cache" to true
    )

    private val main = File("src/main")

    @Test
    fun everyDataStoreIsAccountedFor() {
        val declared = declaredStores()
        assertTrue("DataStore 선언을 못 찾았다: ${main.absolutePath}", declared.isNotEmpty())
        assertEquals(
            "새 DataStore 가 백업 판단 없이 들어왔다 — 이 표에 답을 적을 것",
            expected.keys.sorted(), declared.sorted()
        )
    }

    /**
     * Both XMLs exclude exactly the stores the ledger says must not travel.
     *
     * Both, because they cover different API levels: a rule added to one of them only is a store
     * that crosses devices on half the fleet.
     */
    @Test
    fun theExclusionsMatchTheLedger() {
        val mustNotTravel = expected.filterValues { !it }.keys
            .map { "datastore/$it.preferences_pb" }.toSortedSet()

        // API ≤30: one list for the whole file.
        assertEquals(
            "backup_rules.xml 의 제외 목록이 원장과 다르다",
            mustNotTravel, excludedIn(File(main, "res/xml/backup_rules.xml").readText())
        )

        // API 31+: two blocks, checked apart. Reading the file as one list would pass with a rule
        // present in only one of them — and then the store crosses on a device transfer while
        // staying out of a cloud backup, or the other way round. Found by review.
        val rules = File(main, "res/xml/data_extraction_rules.xml").readText()
        listOf("cloud-backup", "device-transfer").forEach { block ->
            val body = Regex("<$block>(.*?)</$block>", RegexOption.DOT_MATCHES_ALL)
                .find(rules)?.groupValues?.get(1)
                ?: error("data_extraction_rules.xml 에 <$block> 블록이 없다")
            assertEquals("<$block> 의 제외 목록이 원장과 다르다", mustNotTravel, excludedIn(body))
        }
    }

    private fun excludedIn(xml: String) =
        EXCLUDE.findAll(xml).map { it.groupValues[1] }.toSortedSet()

    /**
     * Nothing this app deletes on start is also carried between devices.
     *
     * A store on both lists would be destroyed here and handed back by the next device transfer,
     * which is a decision undone by a backup rule rather than by anyone. The reverse is not a rule
     * — `fxi_access_epoch` must not travel and must certainly not be deleted — so this checks one
     * direction only.
     */
    @Test
    fun theRetiredStoresAreAlsoKeptOutOfBackup() {
        RetiredStores.NAMES.forEach {
            assertEquals(
                "$it 를 시작할 때 지우면서 백업으로는 돌려받는다", false, expected[it]
            )
        }
    }

    /**
     * The allowlist switch has not happened unnoticed.
     *
     * Written as a condition on `<include>` rather than as "the new file is not excluded", which
     * would pass just as happily after the switch and tell nobody that every eligible store now
     * needs listing. It is vacuously true today and fires on the commit that converts the file.
     */
    @Test
    fun theAllowlistSwitchHasNotHappenedUnnoticed() {
        listOf("backup_rules.xml", "data_extraction_rules.xml").forEach { name ->
            val xml = File(main, "res/xml/$name").readText()
            assertTrue(
                "$name 이 allowlist 로 바뀌었다 — 원장의 travelling store 를 전부 include 에 올릴 것",
                !xml.contains("<include")
            )
        }
    }

    /**
     * `by preferencesDataStore(name = …)`, however the name is written.
     *
     * A literal or a constant: `fxi_access_epoch` is declared through a constant, and a scan that
     * only understood literals left it off the first draft of this ledger — the same silent
     * omission this test exists to prevent, one level up.
     */
    private fun declaredStores(): Set<String> = main.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { file ->
            val text = file.readText()
            DECLARATION.findAll(text).map { match ->
                match.groupValues[1].ifEmpty { constantValue(text, match.groupValues[2]) }
            }
        }
        .toSet()

    /** The literal a same-file `const val` holds, so a constant-named store still lands here. */
    private fun constantValue(text: String, name: String): String =
        Regex("const\\s+val\\s+$name\\s*(?::\\s*String\\s*)?=\\s*\"([^\"]+)\"")
            .find(text)?.groupValues?.get(1)
            ?: error("$name 의 값을 못 찾았다 — 다른 파일에서 선언됐다면 스캔을 넓힐 것")

    private companion object {
        val DECLARATION =
            Regex("preferencesDataStore\\(\\s*name\\s*=\\s*(?:\"([^\"]*)\"|([A-Za-z_][A-Za-z0-9_]*))")
        val EXCLUDE = Regex("<exclude\\s+domain=\"file\"\\s+path=\"([^\"]+)\"")
    }
}
