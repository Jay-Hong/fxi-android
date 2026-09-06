package com.jay.fxi.ui.screen

import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.domain.model.AuthState

/**
 * What the Root surface shows.
 *
 * Deliberately smaller than the D23 state set: several access states share one surface, and the
 * destination is the only thing Root needs. Keeping the collapse *here* rather than inside the
 * composable is what makes it testable on the JVM — the unit-test classpath has no Compose
 * artifacts (`app/build.gradle.kts`), so a mapping written inside `@Composable` could only be
 * exercised in the androidTest lane.
 */
enum class RootDestination {
    /** Firebase has not yet reported a restored user. Not the same as signed out. */
    Splash,

    Login,

    /** Real free snapshot. `ANDROID_V2_PLAN.md` §S2 — never a preview or a sample. */
    FreeSnapshot,

    Premium
}

/**
 * The single Root decision: `(auth, access) -> surface`.
 *
 * Auth outranks access. A stale [PremiumAccessState.PremiumConfirmed] left in memory must not
 * survive a sign-out, so [AuthState.SignedOut] maps to [RootDestination.Login] regardless.
 *
 * The inner `when` is exhaustive over the sealed type with **no `else`**: when S10 adds
 * `DeletionPending`, the plan requires it to outrank this mapping
 * (`ANDROID_V2_PLAN.md` D23 table), and a missing branch must fail to compile rather than land
 * silently in [RootDestination.FreeSnapshot].
 *
 * KRX is not consulted. It is a capability inside the premium surface, not a Root destination.
 */
fun rootDestinationFor(auth: AuthState, access: PremiumAccessState): RootDestination =
    when (auth) {
        AuthState.Unknown -> RootDestination.Splash
        AuthState.SignedOut -> RootDestination.Login
        is AuthState.SignedIn -> when (access) {
            // Only a fresh stable ACTIVE opens the premium surface.
            PremiumAccessState.PremiumConfirmed -> RootDestination.Premium

            // Everything else is the free surface, including Rejected: the D23 table sends a
            // typed `premium_required` to "즉시 무료 전환", not back to login.
            PremiumAccessState.NoGrant,
            PremiumAccessState.FreeConfirmed,
            PremiumAccessState.Rejected,
            is PremiumAccessState.Pending -> RootDestination.FreeSnapshot
        }
    }
