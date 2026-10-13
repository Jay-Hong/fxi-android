package com.jay.fxi.data.auth

/**
 * S5-4 slice 3: the signed-in user's profile as the auth screen state reads it, and notice of its changes. Production observes
 * FirebaseAuth; a fixture build type binds its own, over the same identity source as its token source and fence stream.
 *
 * [register] delegates FirebaseAuth.AuthStateListener registration and removal. The SDK delivers the initial notification
 * after registration, including when signed out, and subsequent auth-state notifications on the UI thread.
 * Closing the handle unregisters the listener; it does not guarantee cancellation of an already queued callback.
 * [currentUser] selects the user at the time of the call. Its handle reads that selected user's profile when requested.
 */
internal interface AuthUserObservation {
    fun currentUser(): ObservedAuthUserHandle?

    fun register(onChange: () -> Unit): AutoCloseable
}

/** The selected user; readProfile must read this user, without selecting another identity. */
internal interface ObservedAuthUserHandle {
    val uid: String
    fun readProfile(): ObservedAuthUser
}

/** The signed-in user's fields the app shows; [providerId] is the first sign-in provider that is not Firebase's own. */
internal data class ObservedAuthUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val providerId: String?
)

internal fun observedAuthUser(
    uid: String,
    email: String?,
    displayName: String?,
    photoUrl: String?,
    providerIds: List<String>
): ObservedAuthUser = ObservedAuthUser(uid, email, displayName, photoUrl, providerIds.firstOrNull { it != "firebase" })
