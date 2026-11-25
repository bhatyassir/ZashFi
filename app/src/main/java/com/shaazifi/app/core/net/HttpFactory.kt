package com.shaazifi.app.core.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * HttpFactory builds properly configured OkHttpClient instances enforcing
 * AddressGuard rules, redirect validation, and TrustStore TOFU certificate validation.
 */
object HttpFactory {

    fun createClient(
        trustStore: TrustStore = TrustStore(),
        dns: AddressGuardDns = SystemAddressGuardDns,
        connectTimeoutSeconds: Long = 10,
        readTimeoutSeconds: Long = 30
    ): OkHttpClient {
        val guardInterceptor = AddressGuardInterceptor(dns)
        return OkHttpClient.Builder()
            .dns(OkHttpAddressGuardDns(dns))
            .addInterceptor(guardInterceptor)
            .addNetworkInterceptor(guardInterceptor)
            .sslSocketFactory(trustStore.sslSocketFactory, trustStore.trustManager)
            .hostnameVerifier(trustStore.hostnameVerifier)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
