package com.jay.fxi.data.entitlements

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the production DataStore wiring, which the current JVM behavioural tests do not exercise.
 *
 * [DataStoreAccessEpochStoreTest] builds its own DataStore over a temporary file and installs the
 * handler itself, so it proves what the handler does and nothing about whether the app installs
 * one. Measured: deleting `corruptionHandler = …` from the delegate left that whole suite green.
 *
 * This is a text check on the delegate's arguments. It does not prove the app resolves this
 * property, and a handler reached some other way would not be recognised here.
 */
class AccessEpochStoreWiringTest {

    private val source: String by lazy {
        val file = File("src/main/java/com/jay/fxi/data/entitlements/DataStoreAccessEpochStore.kt")
        assertTrue(
            "소스를 찾지 못했다 — 이 시험은 파일을 읽으므로 경로가 바뀌면 조용히 통과하면 안 된다: ${file.absolutePath}",
            file.isFile
        )
        file.readText()
    }

    @Test
    fun theProductionDelegateInstallsTheCorruptionHandler() {
        val delegate = Regex("""by\s+preferencesDataStore\s*\((.*?)\)\s*\n""", RegexOption.DOT_MATCHES_ALL)
            .find(source)
            ?.groupValues
            ?.get(1)

        assertTrue("preferencesDataStore 위임을 찾지 못했다", delegate != null)
        assertTrue(
            "운영 저장소가 손상 처리기 없이 열린다: $delegate",
            delegate!!.contains("corruptionHandler = accessEpochCorruptionHandler(")
        )
        assertTrue("이름 상수를 쓰지 않는다: $delegate", delegate.contains("name = ACCESS_EPOCH_STORE_NAME"))
    }
}
