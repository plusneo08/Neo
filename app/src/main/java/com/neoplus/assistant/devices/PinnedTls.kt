package com.neoplus.assistant.devices

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The PC uses a self-signed certificate, so normal CA validation can't apply. Instead the phone trusts exactly one
 * certificate: the SHA-256 fingerprint it received through the QR code / manual pairing. Anything else is rejected.
 */
object PinnedTls {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun client(fingerprintHex: String, callTimeoutSec: Long = 0): OkHttpClient {
        val want = fingerprintHex.trim().lowercase()
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("Client certificates are not used")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val got = sha256Hex(chain[0].encoded)
                if (!MessageDigest.isEqual(got.toByteArray(), want.toByteArray()))
                    throw CertificateException("PC certificate does not match the paired fingerprint")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true } // identity is proven by the pinned certificate, not the (changing) LAN IP
            .connectTimeout(6, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
