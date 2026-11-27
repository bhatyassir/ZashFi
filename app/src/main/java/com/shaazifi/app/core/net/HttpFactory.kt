package com.shaazifi.app.core.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * HttpFactory builds properly configured OkHttpClient instances enforcing
 * AddressGuard rules, redirect validation, optional LAN Wi-Fi socket binding,
 * and TrustStore TOFU certificate validation.
 */
object HttpFactory {

    fun createClient(
        trustStore: TrustStore = TrustStore(),
        dns: AddressGuardDns = SystemAddressGuardDns,
        connectTimeoutSeconds: Long = 10,
        readTimeoutSeconds: Long = 30,
        writeTimeoutSeconds: Long = 30,
        callTimeoutSeconds: Long = 45,
        socketFactory: SocketFactory? = null
    ): OkHttpClient {
        val guardInterceptor = AddressGuardInterceptor(dns)
        return OkHttpClient.Builder()
            .dns(OkHttpAddressGuardDns(dns))
            .addInterceptor(guardInterceptor)
            .apply {
                if (socketFactory != null) socketFactory(socketFactory)
            }
            .sslSocketFactory(trustStore.sslSocketFactory, trustStore.trustManager)
            .hostnameVerifier(trustStore.hostnameVerifier)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            // Redirects are followed by AddressGuardInterceptor so every hop is validated
            // before a connection is opened (OkHttp's own follow-up would bypass the app interceptor).
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
