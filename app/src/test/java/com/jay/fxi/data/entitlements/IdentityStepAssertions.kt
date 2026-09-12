package com.jay.fxi.data.entitlements

import org.junit.Assert.assertTrue

/**
 * Reading an [IdentityStep] in a test.
 *
 * An attempt-owned hold used to come back as null or false, and an edit failing with no attempt to
 * own it used to throw. These name one of them each, so an assertion about an attempt-owned hold
 * cannot pass on an unowned persistence hold.
 */

/** Asserts the event was applied, and hands back what it completed. */
internal fun IdentityStep.applied(what: String = "신원 사건"): IdentityCompletion {
    assertTrue("$what: 적용되지 않았다 — $this", this is IdentityStep.Applied)
    return (this as IdentityStep.Applied).completion
}

/** The generation a bind hands back, for the follow-up query that belongs to it. */
internal fun IdentityStep.boundGeneration(what: String = "신원 사건"): AccessDecisionGeneration =
    checkNotNull(applied(what).queryGeneration) { "$what: 질의 세대를 넘기지 않았다" }

/** Asserts an open sign-out is holding the event, so the same event is retried in place. */
internal fun IdentityStep.heldByAttempt(what: String = "신원 사건"): IdentityStep.AwaitAttempt {
    assertTrue("$what: 열린 로그아웃이 붙들고 있어야 한다 — $this", this is IdentityStep.AwaitAttempt)
    return this as IdentityStep.AwaitAttempt
}

/** Asserts the task's own edit is unresolved, with no attempt to own the outcome. */
internal fun IdentityStep.heldByPersistence(what: String = "신원 사건"): IdentityStep.AwaitPersistence {
    assertTrue("$what: 보류로 남아 있어야 한다 — $this", this is IdentityStep.AwaitPersistence)
    return this as IdentityStep.AwaitPersistence
}
