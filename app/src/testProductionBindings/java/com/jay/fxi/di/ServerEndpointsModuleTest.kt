package com.jay.fxi.di

import com.jay.fxi.util.ApiConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** S5-4 slice 4a: the production build types bind the production servers. */
class ServerEndpointsModuleTest {

    @Test
    fun `S5-4 4a the production endpoints are ApiConfig's`() {
        assertEquals(ServerEndpoints(ApiConfig.BASE_URL, ApiConfig.WS_URL), ServerEndpointsModule.provideServerEndpoints())
    }
}
