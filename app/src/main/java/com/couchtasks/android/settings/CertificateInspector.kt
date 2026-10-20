package com.couchtasks.android.settings

import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory

object CertificateInspector {
    fun caCertificateSha256Fingerprint(certificate: InputStream): String {
        val ca = certificate.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        } as? X509Certificate ?: throw CertificateException("Selected CA certificate is not X.509")
        if (ca.basicConstraints < 0) {
            throw CertificateException("Selected certificate is not a CA certificate")
        }
        return ca.sha256Fingerprint()
    }

    fun clientPkcs12Sha256Fingerprint(
        clientP12: InputStream,
        password: CharArray,
    ): String {
        val clientKeyStore = KeyStore.getInstance("PKCS12").apply {
            clientP12.use { load(it, password) }
        }
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).init(clientKeyStore, password)
        val alias = clientKeyStore.firstKeyEntryAlias()
            ?: throw CertificateException("Selected .p12 does not contain a client private key")
        val certificate = (clientKeyStore.getCertificateChain(alias)?.firstOrNull()
            ?: clientKeyStore.getCertificate(alias)) as? X509Certificate
            ?: throw CertificateException("Selected .p12 does not contain an X.509 client certificate")
        return certificate.sha256Fingerprint()
    }
}

private fun KeyStore.firstKeyEntryAlias(): String? {
    val aliases = aliases()
    while (aliases.hasMoreElements()) {
        val alias = aliases.nextElement()
        if (isKeyEntry(alias)) return alias
    }
    return null
}

private fun X509Certificate.sha256Fingerprint(): String = MessageDigest
    .getInstance("SHA-256")
    .digest(encoded)
    .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xff) }
