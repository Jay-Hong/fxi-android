package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.PurgeScope

/**
 * Why a namespace is owed a purge (purger 설계 v3 final §3.1).
 *
 * The cause is named by whichever path decided the teardown, not inferred later. It decides one
 * thing this slice cares about: whether a user's own stored preferences may be deleted, which only
 * an account deletion may do. Everything else it is used for is accounting.
 *
 * [UNKNOWN] is what a journal entry written before causes existed reads as. It is deliberately the
 * weakest: it authorises the derived-data work and nothing about preferences, because a journal
 * entry with no cause is not evidence that an account was deleted.
 */
enum class PurgeCause {
    ACCOUNT_DELETION,
    SIGN_OUT,
    IDENTITY_SWITCH,
    PREMIUM_REVOKE,
    CAPABILITY_REVOKE,
    UNVERIFIED_START,
    UNCLEAN_RESTART,
    UNKNOWN
}

/**
 * What kind of data a storage surface holds, and therefore who may delete it.
 *
 * `ANDROID_V2_PLAN.md` §9.1 gives legacy rate/graph stores to the slices that cut their consumers
 * over, and §7 S1 keeps a capability revoke away from a user's own preferences. So "is this user
 * data" is not enough to decide deletion: the surface also has to be one this purger owns.
 */
enum class PurgeClassification {
    /** Server-derived data of a retired namespace. This purger deletes it. */
    DERIVED_HERE,

    /** The user's own stored preferences. Only an authorised account deletion removes them. */
    ACCOUNT_DELETION_ONLY,

    /** Legacy stores whose deletion belongs to the slice that cuts their consumer over (§9.1). */
    CUTOVER_OWNED,

    /** Kept unless proven incompatible; S10 owns that check (§9.1, `news_cache.json`). */
    UNDER_REVIEW,

    /** User data another path is responsible for clearing. Its hand-over is not our completion. */
    HANDED_OVER,

    /** Control plane, device or install state. Must survive a purge. */
    NOT_USER_DATA
}

/**
 * One storage surface, named so that a purge can talk about it without holding it.
 *
 * [id] is stable and appears in `PurgeResult.Deferred` reasons, so it is written for a reader of a
 * log rather than for code. [scopes] is which axis's retirement reaches this surface: a KRX
 * capability revoke must not reach a surface that only ever held user-axis data.
 */
data class PurgeTarget(
    val id: String,
    val classification: PurgeClassification,
    val scopes: Set<PurgeScope>,
    /** Who deletes it when this purger may not — a slice name, or the path that owns it. */
    val owner: String,
    val note: String
)

/**
 * The storage surfaces this app's own data code manages, classified.
 *
 * Surveyed from the code rather than from the plan: eleven preference DataStores, three
 * `SharedPreferences` files and the two file shapes under `filesDir`. There is no Room, SQLite or
 * WorkManager state of ours. It is **not** an inventory of what a framework or an SDK persists on
 * its own — `NewsDetailOverlay` turns on WebView DOM storage, for one — and `PurgeManifestTest`
 * recognises the declaration forms we write today, so its positive controls do not prove that a
 * new storage API, a new declaration form or another source set would be seen.
 *
 * A surface missing from this list is the failure this list exists to prevent, which is why
 * `PurgeManifestTest` pins the set rather than the classifications alone.
 *
 * The S3 topic last-known store is classified as `DERIVED_HERE`, but its deletion adapter is not
 * wired. A user-axis purge therefore remains Deferred until that adapter exists; see
 * [com.jay.fxi.data.entitlements.purge.ManifestScopePurger].
 */
object PurgeManifest {

    val TARGETS: List<PurgeTarget> = listOf(
        PurgeTarget(
            id = "datastore:fxi_backupable_user_intent",
            classification = PurgeClassification.ACCOUNT_DELETION_ONLY,
            scopes = setOf(PurgeScope.USER),
            owner = "account deletion obligation",
            note = "graph selections per UID, audience and tab; deletes only the authorized UID; " +
                "excluded from backup in B2 pending S1 admission verification"
        ),
        PurgeTarget(
            id = "datastore:fxi_user_intent",
            classification = PurgeClassification.ACCOUNT_DELETION_ONLY,
            scopes = setOf(PurgeScope.USER),
            owner = "account deletion obligation",
            note = "rate row order and hidden set, owner-stamped per concern (`rate_row_owner_uid`) " +
                "because the store is meant to hold more than one; D27 keeps it backup-eligible user intent"
        ),
        PurgeTarget(
            id = "datastore:fxi_free_graph",
            classification = PurgeClassification.ACCOUNT_DELETION_ONLY,
            scopes = setOf(PurgeScope.USER),
            owner = "account deletion obligation",
            note = "visible free graph series per tab, owner-stamped"
        ),
        PurgeTarget(
            id = "datastore:fxi_free_tab",
            classification = PurgeClassification.ACCOUNT_DELETION_ONLY,
            scopes = setOf(PurgeScope.USER),
            owner = "account deletion obligation",
            note = "last opened free tab, owner-stamped"
        ),
        PurgeTarget(
            id = "datastore:fxi_cache#rates",
            classification = PurgeClassification.CUTOVER_OWNED,
            scopes = setOf(PurgeScope.USER),
            owner = "S3",
            note = "`rates`/`rates_timestamp`; deleted with the legacy rate consumer cutover (§9.1)"
        ),
        PurgeTarget(
            id = "datastore:fxi_cache#last_bank",
            classification = PurgeClassification.CUTOVER_OWNED,
            scopes = setOf(PurgeScope.USER),
            owner = "S7",
            note = "`last_bank_*`; v1 ownership is unprovable, so S7 deletes it after its store lands (§9.1)"
        ),
        PurgeTarget(
            id = "datastore:fxi_graph_preferences",
            classification = PurgeClassification.CUTOVER_OWNED,
            scopes = setOf(PurgeScope.USER),
            owner = "S4",
            note = "v1 series ids; reset by the Graph V2 cutover (§9.1)"
        ),
        PurgeTarget(
            id = "file:graph_cache",
            classification = PurgeClassification.CUTOVER_OWNED,
            scopes = setOf(PurgeScope.USER),
            owner = "S4",
            note = "`graph_cache_v1_*.json` and the legacy names; no reader or writer remains — only account-deletion cleanup removes " +
                "them until the S4 cutover migration (§9.1)"
        ),
        PurgeTarget(
            id = "file:graph_v2_general",
            classification = PurgeClassification.DERIVED_HERE,
            scopes = setOf(PurgeScope.USER),
            owner = "S4",
            note = "validated server Graph V2 general components per UID/user epoch"
        ),
        PurgeTarget(
            id = "file:graph_v2_krx",
            classification = PurgeClassification.DERIVED_HERE,
            scopes = setOf(PurgeScope.USER, PurgeScope.CAPABILITY),
            owner = "S4",
            note = "validated server Graph V2 KRX components per UID/user/capability epoch"
        ),
        PurgeTarget(
            id = "file:news_cache",
            classification = PurgeClassification.UNDER_REVIEW,
            scopes = setOf(PurgeScope.USER),
            owner = "S10",
            note = "kept if the compatibility fixture passes; decode failure refetches (§9.1)"
        ),
        PurgeTarget(
            id = "prefs:push_prefs",
            classification = PurgeClassification.HANDED_OVER,
            scopes = setOf(PurgeScope.USER),
            owner = "push registration path",
            note = "`should_register` and the legacy token; cleared by clearLocalRegistrationState"
        ),
        PurgeTarget(
            id = "datastore:fxi_bank_preferences",
            classification = PurgeClassification.HANDED_OVER,
            scopes = setOf(PurgeScope.USER),
            owner = "RetiredStores",
            note = "retired v1 order; swept at every start"
        ),
        PurgeTarget(
            id = "datastore:fxi_access_epoch",
            classification = PurgeClassification.NOT_USER_DATA,
            scopes = emptySet(),
            owner = "access epoch finalizers",
            note = "epochs, markers, purge journal and teardown intent — the control plane itself"
        ),
        PurgeTarget(
            id = "datastore:fxi_push_registration_ledger",
            classification = PurgeClassification.NOT_USER_DATA,
            scopes = emptySet(),
            owner = "push registration path",
            note = "rows the server may still hold a registration for; cleared by its own finalizer"
        ),
        PurgeTarget(
            id = "datastore:fxi_migration_journal",
            classification = PurgeClassification.NOT_USER_DATA,
            scopes = emptySet(),
            owner = "LocalMigrationJournal",
            note = "§9.1 per-target legacy migration progress for this install — not the user's data, and a purge must not rewind it"
        ),
        PurgeTarget(
            id = "datastore:fxi_topic_last_known",
            classification = PurgeClassification.DERIVED_HERE,
            scopes = setOf(PurgeScope.USER),
            owner = "S3",
            note = "FX/Tether/DXY display seeds per UID and user epoch; deletion adapter TODO(S3-R2), so purge remains Deferred"
        ),
        PurgeTarget(
            id = "prefs:alert_prefs",
            classification = PurgeClassification.NOT_USER_DATA,
            scopes = emptySet(),
            owner = "device state",
            note = "whether the notification permission was ever asked for — a device fact, not the user's data"
        ),
        PurgeTarget(
            id = "prefs:free_snapshot",
            classification = PurgeClassification.NOT_USER_DATA,
            scopes = emptySet(),
            owner = "install state",
            note = "refresh jitter seed for this install"
        )
    )

    fun byId(id: String): PurgeTarget? = TARGETS.firstOrNull { it.id == id }
}
