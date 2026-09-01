package com.jay.fxi.admission

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/** Final DNS/socket tripwire for every app-owned OkHttp request. */
internal class ReleaseAdmissionInterceptor(
    private val admitted: () -> Boolean = { ReleaseAdmission.isOpen }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!admitted()) {
            throw IOException("D24 release admission is OFF")
        }
        return chain.proceed(chain.request())
    }
}
