package com.pixelpass.network

import com.pixelpass.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.Response
import java.util.concurrent.TimeUnit
import com.pixelpass.network.ScanRequest
import com.pixelpass.network.ScanResponse

data class LoginRequest(
    val identifier: String,
    val password: String,
    val keepSignedIn: Boolean = true // Default to true or map to a mobile checkbox
)
data class LoginResponse(val message: String, val token: String, val role: String)

interface PixelPassApi {
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("api/scan")
    suspend fun verifyScan(@Body request: ScanRequest): Response<ScanResponse>
}

object RetrofitClient {
    private val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

        // Only log network traffic in Debug mode to protect sensitive data in Production
        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            builder.addInterceptor(loggingInterceptor)
        }

        builder.build()
    }

    val instance: PixelPassApi by lazy {
        Retrofit.Builder()
            // Dynamically pulls the URL based on the current build     type (Debug vs Release)
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PixelPassApi::class.java)
    }
}