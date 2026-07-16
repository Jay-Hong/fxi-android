package com.jay.fxi.data.remote

import com.jay.fxi.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR-039 첫 계측 슬라이스 — X-Client-* 헤더 매핑 회귀 잠금. */
class ClientMetadataTest {

    @Test
    fun headers_containPlatformVersionBuild() {
        val h = ClientMetadata.headers()
        assertEquals("android", h["X-Client-Platform"])
        assertEquals(BuildConfig.VERSION_NAME, h["X-Client-Version"])
        assertEquals(BuildConfig.VERSION_CODE.toString(), h["X-Client-Build"])
        assertEquals(3, h.size)
    }
}
