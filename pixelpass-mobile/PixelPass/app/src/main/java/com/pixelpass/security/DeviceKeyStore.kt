package com.pixelpass.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.pixelpass.core.PixelPassLog
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Device identity for the pass payload.
 *
 * The ECDSA P-256 private key is generated inside the Android Keystore and is
 * never exported: the app only ever asks the Keystore to sign. The matching
 * public key travels in the QR payload so a guard can verify the signature
 * without a network round trip.
 */
class DeviceKeyStore(private val alias: String = DEFAULT_ALIAS) {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun getOrCreateKeyPair(): KeyPair {
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (entry != null) {
            return KeyPair(entry.certificate.publicKey, entry.privateKey)
        }
        return generateKeyPair()
    }

    fun publicKeyBase64(): String =
        Base64.getEncoder().encodeToString(getOrCreateKeyPair().public.encoded)

    fun sign(data: ByteArray): ByteArray {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(getOrCreateKeyPair().private)
        signature.update(data)
        return signature.sign()
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        val keyPair = generator.generateKeyPair()
        PixelPassLog.i(PixelPassLog.QR, "Generated device signing key pair in the Android Keystore")
        return keyPair
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "pixelpass_device_signing_key"
        const val CURVE = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        fun publicKeyFromBase64(encoded: String): PublicKey? = runCatching {
            KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(encoded))
            )
        }.getOrNull()

        fun verify(data: ByteArray, signatureBytes: ByteArray, publicKeyBase64: String): Boolean {
            val publicKey = publicKeyFromBase64(publicKeyBase64) ?: return false
            return runCatching {
                val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
                signature.initVerify(publicKey)
                signature.update(data)
                signature.verify(signatureBytes)
            }.getOrDefault(false)
        }
    }
}
