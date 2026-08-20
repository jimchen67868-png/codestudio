package com.example.aideclone.packaging

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Generates (once) and caches a self-signed debug signing key, so APKs can
 * be signed entirely on-device with no external keytool/keystore tooling.
 * This is the on-device equivalent of Android Studio's debug.keystore —
 * fine for installing/testing your own builds, not for Play Store
 * distribution (that needs a real release key you control and back up).
 */
object KeystoreManager {

    data class SigningIdentity(val privateKey: PrivateKey, val certificate: X509Certificate)

    private var cached: SigningIdentity? = null
    private var providerRegistered = false

    private fun ensureProvider() {
        if (!providerRegistered) {
            // Android ships its own restricted, built-in "BC" provider.
            // Security.addProvider() silently loses to it (same name, and
            // Android won't let an added provider outrank a system one at
            // its existing position) — algorithm lookups like SHA256withRSA
            // then resolve against Android's crippled version instead of
            // our full bcprov-jdk18on one, causing
            // NoSuchAlgorithmException even though the real BC classes are
            // right there on the classpath. Removing the system one and
            // inserting ours at the top priority position is the standard,
            // well-documented fix for this on Android.
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            providerRegistered = true
        }
    }

    fun getOrCreate(storageDir: File): SigningIdentity {
        cached?.let { return it }
        ensureProvider()

        val keyFile = File(storageDir, "debug_key.der")
        val certFile = File(storageDir, "debug_cert.der")

        val identity = if (keyFile.exists() && certFile.exists()) {
            load(keyFile, certFile)
        } else {
            val generated = generate()
            storageDir.mkdirs()
            keyFile.writeBytes(generated.privateKey.encoded)
            certFile.writeBytes(generated.certificate.encoded)
            generated
        }
        cached = identity
        return identity
    }

    private fun load(keyFile: File, certFile: File): SigningIdentity {
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))

        val certFactory = CertificateFactory.getInstance("X.509")
        val certificate = certFile.inputStream().use {
            certFactory.generateCertificate(it) as X509Certificate
        }
        return SigningIdentity(privateKey, certificate)
    }

    private fun generate(): SigningIdentity {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()

        val now = Date()
        // ~30 years, matching the lifetime convention of Android Studio's
        // auto-generated debug.keystore.
        val notAfter = Date(now.time + TimeUnit.DAYS.toMillis(365L * 30))
        val subject = X500Name("CN=AIDEClone Debug, O=AIDEClone")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            now,
            notAfter,
            subject,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider("BC")
            .build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        val certificate = JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder)

        return SigningIdentity(keyPair.private, certificate)
    }
}
