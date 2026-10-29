package com.couchtasks.android.sync

import com.couchtasks.android.settings.sha256Fingerprint
import okhttp3.OkHttpClient
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object MtlsHttpClientFactory {
    fun build(
        clientP12: InputStream,
        clientPassword: CharArray,
        caCertificate: InputStream,
        expectedCaSha256Fingerprint: String,
    ): OkHttpClient {
        val clientKeyStore = KeyStore.getInstance("PKCS12").apply {
            clientP12.use { load(it, clientPassword) }
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(clientKeyStore, clientPassword)
        }

        val certificateFactory = CertificateFactory.getInstance("X.509")
        val ca = caCertificate.use { certificateFactory.generateCertificate(it) } as? X509Certificate
            ?: throw CertificateException("CA certificate is not X.509")
        // The CA document can be replaced after selection; only trust the certificate the user approved.
        if (expectedCaSha256Fingerprint.isBlank() || !ca.sha256Fingerprint().equals(expectedCaSha256Fingerprint, ignoreCase = true)) {
            throw CertificateException("CA certificate changed since it was selected")
        }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("couch-tasks-ca", ca)
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        val trustManager = trustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .single()

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, arrayOf(trustManager), SecureRandom())
        }

        return OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }
}
