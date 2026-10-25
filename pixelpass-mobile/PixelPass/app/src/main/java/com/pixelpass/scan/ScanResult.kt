package com.pixelpass.scan

import com.google.gson.JsonParser
import com.pixelpass.network.ScanResponse
import retrofit2.Response

/** The gateway's answer, reduced to what the queue and the screen need. */
data class ScanRemoteResult(
    val statusCode: Int,
    val access: String?,
    val reason: String?
) {

    val granted: Boolean
        get() = access.equals("GRANTED", ignoreCase = true)

    /**
     * Transport failures are reported as status 0 and gateway faults as 5xx;
     * both are worth retrying, while any other refusal is a final decision.
     */
    val isTransient: Boolean
        get() = statusCode == 0 || statusCode >= 500

    companion object {
        /** No HTTP answer at all: airplane mode, dead Wi-Fi or a timeout. */
        val TRANSPORT_ERROR = ScanRemoteResult(statusCode = 0, access = null, reason = null)
    }
}

/** What the guard sees once a scan has been through the local checks. */
data class ScanOutcome(
    val granted: Boolean,
    val headline: String,
    val detail: String,
    val queuedForSync: Boolean = false
)

/** Result of one replay pass over the offline queue. */
data class SyncReport(
    val attempted: Int,
    val synced: Int,
    val failed: Int,
    val retryable: Boolean
)

/**
 * Flattens a Retrofit response, including the JSON error body the gateway sends
 * with 401/403 refusals, into [ScanRemoteResult].
 */
fun toRemoteResult(response: Response<ScanResponse>): ScanRemoteResult {
    val body = response.body()
    val errorReason = response.errorBody()?.string()?.let { raw ->
        runCatching { JsonParser.parseString(raw).asJsonObject.get("reason")?.asString }.getOrNull()
    }

    return ScanRemoteResult(
        statusCode = response.code(),
        access = body?.access,
        reason = body?.reason ?: errorReason
    )
}
