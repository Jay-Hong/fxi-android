package com.jay.fxi.data.auth

import com.jay.fxi.di.AuthModule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5-4 slice 3: structural checks for the user observation port. The JVM tests cannot construct FirebaseAuth or AuthViewModel
 * (no fixture and no mock library), so these read source text, as FirebaseAuthTokenSourceWiringTest does. They pin
 * source-text names and direct call spellings only. Comments and strings are not parsed, and aliases or helper indirection are
 * not covered: they do not prove how many notices arrive, on which thread, or that a closed handle hears nothing more. The port first runs
 * through AuthViewModel in the S5-4 slice 4 emulator test.
 */
class AuthUserObservationWiringTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("소스를 찾지 못했다 — 경로가 바뀌면 조용히 통과하면 안 된다: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val viewModel by lazy { source("src/main/java/com/jay/fxi/ui/auth/AuthViewModel.kt") }
    private val firebase by lazy { source("src/main/java/com/jay/fxi/data/auth/FirebaseAuthUserObservation.kt") }

    @Test
    fun `S5-4 slice 3 the auth screen state observes the user only through the port`() {
        assertEquals("one registration", 1, Regex("""\busers\.register\(""").findAll(viewModel).count())
        assertTrue("the registration keeps its handle",
            Regex("""private val userRegistration: AutoCloseable = users\.register\(onUserChanged\)""").containsMatchIn(viewModel))
        val cleared = Regex("""override fun onCleared\(\) \{([^}]*)\}""").find(viewModel)?.groupValues?.get(1).orEmpty()
        assertTrue("onCleared closes the handle", cleared.contains("userRegistration.close()"))
        val change = Regex("""private val onUserChanged: \(\) -> Unit = \{\s*viewModelScope\.launch \{\s*val user = users\.currentUser\(\)""")
        assertTrue("the user is read inside the launched change", change.containsMatchIn(viewModel))
        assertEquals("one .currentUser spelling in source text", 1, Regex("""\.currentUser\b""").findAll(viewModel).count())
        val read = viewModel.indexOf("val profile = user.readProfile()")
        assertEquals("one profile read", 1, Regex("""\breadProfile\(""").findAll(viewModel).count())
        val completions = Regex("""subscriptionManager\.onAuthCompleted\(""").findAll(viewModel).map { it.range.first }.toList()
        assertTrue("the profile is read after the user is selected and after every RevenueCat completion, as before",
            completions.isNotEmpty() && completions.all { it < read } && viewModel.indexOf("val user = users.currentUser()") in 0 until read)
        assertFalse("no Firebase listener of its own", Regex("""AuthStateListener|addAuthStateListener|removeAuthStateListener""").containsMatchIn(viewModel))
    }

    @Test
    fun `S5-4 slice 3 the Firebase observation matches the delegation source shape`() {
        assertEquals("one direct listener construction spelling", 1,
            Regex("""\bFirebaseAuth\s*\.\s*AuthStateListener\s*(?:\{|\()""").findAll(firebase).count())
        assertTrue("the listener directly calls onChange",
            Regex("""val listener = FirebaseAuth\.AuthStateListener \{ onChange\(\) \}""").containsMatchIn(firebase))
        assertEquals("one direct add spelling", 1,
            Regex("""\bauth\s*\.\s*addAuthStateListener\s*\(""").findAll(firebase).count())
        assertEquals("one direct remove spelling", 1,
            Regex("""\bauth\s*\.\s*removeAuthStateListener\s*\(""").findAll(firebase).count())
        assertTrue("the handle removes the same listener",
            firebase.contains("AutoCloseable { auth.removeAuthStateListener(listener) }"))
        assertEquals("one direct onChange() or onChange.invoke() spelling", 1,
            Regex("""\bonChange\s*(?:\(\s*\)|\.\s*invoke\s*\(\s*\))""").findAll(firebase).count())
        assertFalse("no listed dispatch spelling",
            Regex("""\b(?:Handler|Dispatchers|Executor)\b|\b(?:post|launch)\s*(?:\(|\{)""").containsMatchIn(firebase))
    }

    @Test
    fun `S5-4 slice 3 the identity bindings left the shared auth module`() {
        val provided = AuthModule::class.java.declaredMethods.map { it.returnType }
        assertFalse("no token source binding in AuthModule", provided.contains(AuthTokenSource::class.java))
        assertFalse("no fence stream binding in AuthModule", provided.contains(AuthFenceStream::class.java))
        assertFalse("no user observation binding in AuthModule", provided.contains(AuthUserObservation::class.java))
    }
}
