package com.jay.fxi.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.util.SortedSet
import javax.xml.parsers.DocumentBuilderFactory

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
        "fxi_user_intent" to true,       // rate row order and hidden set; last selected bank, owner-stamped per concern
        "fxi_free_graph" to true,        // which graph series the free surface draws
        "fxi_free_tab" to true,          // the tab to reopen on

        // S4 B2b: graph selections per UID, audience and tab. Named for where it is going - pure, UID-keyed intent that may
        // travel - but held out for now: the XMLs cannot make a rule depend on a UID inside a file, and turning backup on is
        // S1's change to verify (R4c/S4 b2_design_codex.r1 §2, "B2 중간 상태의 백업 보류"). Flip it here when S1 does.
        "fxi_backupable_user_intent" to false,

        // Control-plane provenance. An epoch restored onto a different install points cleanup at a
        // namespace that never existed there.
        "fxi_access_epoch" to false,

        // Which `(uid, token)` registrations the server may hold, and which are owed a DELETE. An entry
        // restored onto another install names that device's token; resuming it would DELETE the other
        // device's registration.
        "fxi_push_registration_ledger" to false,

        // `ANDROID_V2_PLAN.md §9.1`: the migration journal is excluded, so the journal marker itself is never restored.
        // The declared legacy stores' backup policy is tracked below (`fxi_cache`); the retired graph preference backing
        // file still belongs to the S4 cutover, independently of whether a DataStore is declared for it.
        "fxi_migration_journal" to false,

        // S3 topic last-known seeds: server-derived prices bound to one UID and user epoch — another install has neither.
        "fxi_topic_last_known" to false,

        // v1, with **no UID at all**. `ANDROID_V2_PLAN.md §7 S1.5` requires that a backup restore
        // not attribute v1 bank preferences to another UID, and a file that never recorded one
        // cannot help doing exactly that. Excluded here, and deleted outright on start — see
        // `RetiredStores`, and `theRetiredStoresAreAlsoKeptOutOfBackup` below for why both.
        "fxi_bank_preferences" to false,

        // The shared v1 cache: S3 deletes its retired rate keys and S7 E5b4b its `last_bank_*` keys, which carry no UID.
        // `ANDROID_V2_PLAN.md` §9.1 keeps legacy cache out of backup, so a restore cannot bring the keys back to another
        // install. The re-sweep after LEGACY_DELETED still stays: an older backup, a downgrade or a retired writer can.
        "fxi_cache" to false
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
        // The ledger itself: §9.1 keeps the journal home, and user intent is what travels.
        assertTrue("원장이 journal 을 내보낸다", "datastore/fxi_migration_journal.preferences_pb" in mustNotTravel)
        assertTrue("원장이 user intent 를 막는다", "datastore/fxi_user_intent.preferences_pb" !in mustNotTravel)

        // API ≤30: one list for the whole file.
        val backup = parse(File(main, "res/xml/backup_rules.xml").readText())
        assertEquals("full-backup-content", backup.tagName)
        assertEquals("backup_rules.xml 의 제외 목록이 원장과 다르다", mustNotTravel, excludedIn(backup))

        // API 31+: two blocks, checked apart. Reading the file as one list would pass with a rule
        // present in only one of them — and then the store crosses on a device transfer while
        // staying out of a cloud backup, or the other way round. Found by review.
        val rules = parse(File(main, "res/xml/data_extraction_rules.xml").readText())
        assertEquals("data-extraction-rules", rules.tagName)
        val blocks = childElements(rules)
        assertEquals("블록이 하나씩 있어야 한다", listOf("cloud-backup", "device-transfer"), blocks.map { it.tagName })
        blocks.forEach { block ->
            assertEquals("<${block.tagName}> 의 제외 목록이 원장과 다르다", mustNotTravel, excludedIn(block))
        }
    }

    /**
     * A rule left only inside an XML comment is not a rule. These inputs keep the commented tag's text whole, so a scan of
     * the raw text would still count it; only a parse that reads elements tells them apart.
     */
    @Test
    fun anExcludeLeftOnlyInACommentDoesNotCount() {
        val cache = "datastore/fxi_cache.preferences_pb"
        val backupText = File(main, "res/xml/backup_rules.xml").readText()
        val backupCommented = commentOut(backupText, null, cache)
        assertEquals("fixture 가 태그 글자를 지웠다", backupText.split(cache).size, backupCommented.split(cache).size)
        assertTrue(cache in excludedIn(parse(backupText)))
        assertTrue("주석 속 exclude 를 셌다", cache !in excludedIn(parse(backupCommented)))

        val rulesText = File(main, "res/xml/data_extraction_rules.xml").readText()
        listOf("cloud-backup", "device-transfer").forEach { block ->
            val commented = commentOut(rulesText, block, cache)
            assertEquals("fixture 가 태그 글자를 지웠다", rulesText.split(cache).size, commented.split(cache).size)
            val parsed = childElements(parse(commented)).associateBy { it.tagName }
            assertTrue("<$block> 주석 속 exclude 를 셌다", cache !in excludedIn(parsed.getValue(block)))
            val other = parsed.keys.single { it != block }
            assertTrue("<$other> 의 실제 exclude 를 잃었다", cache in excludedIn(parsed.getValue(other)))
        }
    }

    /** The parsed document's root. Comments are dropped by the parser; DOCTYPE is refused. */
    private fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.isIgnoringComments = true
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }

    private fun childElements(parent: Element): List<Element> =
        (0 until parent.childNodes.length).map { parent.childNodes.item(it) }.filterIsInstance<Element>()

    /** The paths [block]'s own child elements exclude. Anything but a file `<exclude>`, or a repeat, fails. */
    private fun excludedIn(block: Element): SortedSet<String> {
        val children = childElements(block)
        children.forEach {
            assertEquals("<${block.tagName}> 안에 exclude 아닌 요소", "exclude", it.tagName)
            assertEquals("domain 이 file 이 아니다: ${it.getAttribute("path")}", "file", it.getAttribute("domain"))
        }
        val paths = children.map { it.getAttribute("path") }
        assertEquals("<${block.tagName}> 에 같은 exclude 가 둘", paths.size, paths.toSet().size)
        return paths.toSortedSet()
    }

    /** [xml] with [path]'s exclude tag, inside [block] when one is named, wrapped in a comment. */
    private fun commentOut(xml: String, block: String?, path: String): String {
        val tag = "<exclude domain=\"file\" path=\"$path\" />"
        val start = if (block == null) 0 else xml.indexOf("<$block>")
        val end = if (block == null) xml.length else xml.indexOf("</$block>", start)
        check(start >= 0 && end >= 0) { "fixture: <$block> 없음" }
        val at = xml.indexOf(tag, start)
        check(at in start until end) { "fixture: $tag 가 <$block> 안에 없다" }
        return xml.substring(0, at) + "<!-- " + tag + " -->" + xml.substring(at + tag.length)
    }

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
    }
}
