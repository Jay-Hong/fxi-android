package com.jay.fxi.data.entitlements

import com.jay.fxi.data.remote.AuthenticatedApiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** The authenticated identity a query ran as, or that the transport would use right now. */
data class EntitlementsIdentity(val ownerUid: String, val authGeneration: Long)

/**
 * Outcome of one entitlement query.
 *
 * The two cases differ in what they can be attributed to. [Answered] carries the identity the
 * transport actually used, which the coordinator must match against the current one — a query the
 * coordinator started as user A can complete as user B, or as a session the transport has since
 * superseded. [Unauthenticated] has no identity because no credential was obtained; it always
 * carries an [EntitlementsOutcome.Indeterminate], which can never grant or tear anything down, so
 * there is nothing to mis-attribute.
 */
sealed interface EntitlementsResult {
    data class Answered(
        val identity: EntitlementsIdentity,
        val outcome: EntitlementsOutcome
    ) : EntitlementsResult

    data class Unauthenticated(val outcome: EntitlementsOutcome) : EntitlementsResult
}

/**
 * One entitlement query, already classified, plus a view of the transport's current identity.
 *
 * Separating this from [PremiumAccessCoordinator] keeps the coordinator free of transport concerns
 * and gives its state machine a seam that does not need a live protected client. (The real source
 * is testable too — the existing client and transport work against a local server double; the seam
 * is for the coordinator's benefit, not because the client resists testing.)
 */
interface EntitlementsSource {
    suspend fun fetch(freshPremium: Boolean): EntitlementsResult

    /** Identity the transport would use now, or null when no credential is available. */
    suspend fun currentIdentity(): EntitlementsIdentity?
}

/** Production source: the existing protected REST transport plus [EntitlementsClassifier]. */
@Singleton
class AuthenticatedEntitlementsSource @Inject constructor(
    private val api: AuthenticatedApiClient
) : EntitlementsSource {

    override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
        // Acquiring the credential is part of the query, not a precondition of it. Letting an
        // AuthUnavailableException escape would skip classification entirely, so the coordinator
        // would neither preserve the existing grant nor schedule a re-check.
        val owner = try {
            api.captureSnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return EntitlementsResult.Unauthenticated(EntitlementsClassifier.classify(error))
        }
        val outcome = try {
            EntitlementsClassifier.classify(api.getEntitlements(owner, freshPremium))
        } catch (cancelled: CancellationException) {
            // Includes AuthIdentityChangedException: the owner moved while the token was in use,
            // so there is no answer to classify and nothing to apply.
            throw cancelled
        } catch (error: Throwable) {
            EntitlementsClassifier.classify(error)
        }
        return EntitlementsResult.Answered(
            identity = EntitlementsIdentity(owner.uid, owner.authGeneration),
            outcome = outcome
        )
    }

    override suspend fun currentIdentity(): EntitlementsIdentity? = try {
        api.captureIdentityFence().let { EntitlementsIdentity(it.uid, it.authGeneration) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
}
