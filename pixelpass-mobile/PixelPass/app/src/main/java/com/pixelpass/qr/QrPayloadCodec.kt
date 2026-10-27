package com.pixelpass.qr

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pixelpass.security.DeviceKeyStore
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

/**
 * How long one QR pass stays scannable.
 *
 * The holder mints a pass on demand, so the window has to be long enough to
 * line both phones up at the gate, and short enough that a screenshot taken
 * earlier in the day stops working.
 */
const val PASS_TTL_MILLIS: Long = 15 * 60 * 1_000L

/** Tolerated difference between the two phones' clocks during local checks. */
const val PASS_CLOCK_SKEW_MILLIS: Long = 15_000L

/** A payload recovered from a QR string, before the signature is checked. */
data class ParsedPass(
    val payload: PassPayload,
    val signatureBase64: String
)

/**
 * Wire format for the QR pass: compact JSON with one-letter keys.
 *
 * `{"u","t","l","e","n","o","j","k","s"}`. The signature is ECDSA P-256 over
 * the canonical JSON *without* the `s` key, which keeps signing and
 * verification byte-for-byte identical on both phones.
 */
object QrPayloadCodec {

    private const val KEY_USER = "u"
    private const val KEY_ISSUED_AT = "t"
    private const val KEY_GEOHASH = "l"
    private const val KEY_FACE = "e"
    private const val KEY_NONCE = "n"
    private const val KEY_OTP = "o"
    private const val KEY_JWT = "j"
    private const val KEY_DEVICE_KEY = "k"
    private const val KEY_SIGNATURE = "s"

    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** Deterministic, insertion-ordered JSON of everything the signature covers. */
    fun canonicalJson(payload: PassPayload): String = JsonObject().apply {
        addProperty(KEY_USER, payload.userId)
        addProperty(KEY_ISSUED_AT, payload.issuedAtEpochMillis)
        payload.geohash?.takeIf { it.isNotEmpty() }?.let { addProperty(KEY_GEOHASH, it) }
        payload.faceEmbeddingBase64?.takeIf { it.isNotEmpty() }?.let { addProperty(KEY_FACE, it) }
        addProperty(KEY_NONCE, payload.nonce)
        payload.otp?.takeIf { it.isNotEmpty() }?.let { addProperty(KEY_OTP, it) }
        addProperty(KEY_JWT, payload.jwt)
        addProperty(KEY_DEVICE_KEY, payload.devicePublicKeyBase64)
    }.toString()

    fun sign(payload: PassPayload, privateKey: PrivateKey): String {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(signingBytes(payload))
            sign()
        }
        return withSignature(payload, Base64.getEncoder().encodeToString(signature))
    }

    fun withSignature(payload: PassPayload, signatureBase64: String): String =
        JsonParser.parseString(canonicalJson(payload)).asJsonObject
            .apply { addProperty(KEY_SIGNATURE, signatureBase64) }
            .toString()

    fun parse(raw: String): ParsedPass? = runCatching {
        val json = JsonParser.parseString(raw).asJsonObject
        val payload = PassPayload(
            userId = json.requiredString(KEY_USER),
            issuedAtEpochMillis = json.requiredLong(KEY_ISSUED_AT),
            geohash = json.optionalString(KEY_GEOHASH),
            faceEmbeddingBase64 = json.optionalString(KEY_FACE),
            nonce = json.requiredString(KEY_NONCE),
            otp = json.optionalString(KEY_OTP),
            jwt = json.requiredString(KEY_JWT),
            devicePublicKeyBase64 = json.requiredString(KEY_DEVICE_KEY)
        )
        ParsedPass(payload = payload, signatureBase64 = json.requiredString(KEY_SIGNATURE))
    }.getOrNull()

    /** True when the signature was produced by the key embedded in the payload. */
    fun verifySignature(parsed: ParsedPass): Boolean {
        val signatureBytes = runCatching {
            Base64.getDecoder().decode(parsed.signatureBase64)
        }.getOrNull() ?: return false

        return DeviceKeyStore.verify(
            signingBytes(parsed.payload),
            signatureBytes,
            parsed.payload.devicePublicKeyBase64
        )
    }

    private fun signingBytes(payload: PassPayload): ByteArray =
        canonicalJson(payload).toByteArray(Charsets.UTF_8)

    private fun JsonObject.requiredString(key: String): String {
        val value = get(key)?.takeIf { !it.isJsonNull }?.asString
        require(!value.isNullOrBlank()) { "Missing pass field '$key'" }
        return value
    }

    private fun JsonObject.requiredLong(key: String): Long {
        val element = get(key)
        require(element != null && !element.isJsonNull) { "Missing pass field '$key'" }
        return element.asLong
    }

    private fun JsonObject.optionalString(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
}
