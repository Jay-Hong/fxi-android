package com.jay.fxi.data.auth

import com.google.firebase.auth.FirebaseAuth
import javax.inject.Inject

/**
 * S5-4 slice 3: [AuthUserObservation] over FirebaseAuth. It only delegates: the SDK decides when, and on which thread, a
 * registered listener hears of a change, including the first notice after registration.
 */
internal class FirebaseAuthUserObservation @Inject constructor(private val auth: FirebaseAuth) : AuthUserObservation {

    override fun currentUser(): ObservedAuthUserHandle? = auth.currentUser?.let { user ->
        object : ObservedAuthUserHandle {
            override val uid: String
                get() = user.uid

            override fun readProfile(): ObservedAuthUser =
                observedAuthUser(
                    user.uid,
                    user.email,
                    user.displayName,
                    user.photoUrl?.toString(),
                    user.providerData.map { it.providerId }
                )
        }
    }

    override fun register(onChange: () -> Unit): AutoCloseable {
        val listener = FirebaseAuth.AuthStateListener { onChange() }
        auth.addAuthStateListener(listener)
        return AutoCloseable { auth.removeAuthStateListener(listener) }
    }
}
