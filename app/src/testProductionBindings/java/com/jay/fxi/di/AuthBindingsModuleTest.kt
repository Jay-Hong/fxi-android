package com.jay.fxi.di

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthUserObservation
import com.jay.fxi.data.auth.FirebaseAuthTokenSource
import com.jay.fxi.data.auth.FirebaseAuthUserObservation
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import javax.inject.Singleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5-4 slice 3: the production identity bindings, by reflection and source. FirebaseAuthTokenSource needs a FirebaseAuth, which
 * the JVM tests cannot build, so this does not prove at run time that both token bindings hand out one instance; it pins that
 * both take the singleton FirebaseAuthTokenSource and return what they were given. The real graph is assembled in slice 4.
 */
class AuthBindingsModuleTest {

    private fun provider(name: String) = AuthBindingsModule::class.java.declaredMethods.single { it.name == name }

    @Test
    fun `S5-4 slice 3 both token bindings are the one singleton FirebaseAuthTokenSource`() {
        for ((name, type) in listOf("provideAuthTokenSource" to AuthTokenSource::class.java,
            "provideAuthFenceStream" to AuthFenceStream::class.java)) {
            val method = provider(name)
            assertEquals("$name takes the Firebase token source", listOf(FirebaseAuthTokenSource::class.java), method.parameterTypes.toList())
            assertEquals("$name binds its interface", type, method.returnType)
            assertTrue("$name is a singleton binding", method.isAnnotationPresent(Singleton::class.java))
        }
        assertTrue("the Firebase token source is itself a singleton",
            FirebaseAuthTokenSource::class.java.isAnnotationPresent(Singleton::class.java))
        val source = File("src/productionBindings/java/com/jay/fxi/di/AuthBindingsModule.kt").readText()
        assertTrue("the token source binding returns what it was given",
            source.contains("fun provideAuthTokenSource(source: FirebaseAuthTokenSource): AuthTokenSource = source"))
        assertTrue("the fence stream binding returns what it was given",
            source.contains("fun provideAuthFenceStream(source: FirebaseAuthTokenSource): AuthFenceStream = source"))
    }

    @Test
    fun `S5-4 slice 3 the user observation is the Firebase one over the shared FirebaseAuth`() {
        val method = provider("provideAuthUserObservation")
        assertEquals(listOf(FirebaseAuthUserObservation::class.java), method.parameterTypes.toList())
        assertEquals(AuthUserObservation::class.java, method.returnType)
        assertEquals("it observes the FirebaseAuth the app provides", listOf(FirebaseAuth::class.java),
            FirebaseAuthUserObservation::class.java.declaredConstructors.single().parameterTypes.toList())
        val source = File("src/productionBindings/java/com/jay/fxi/di/AuthBindingsModule.kt").readText()
        assertTrue("the user observation binding returns what it was given",
            source.contains("fun provideAuthUserObservation(observation: FirebaseAuthUserObservation): AuthUserObservation = observation"))
    }
}
