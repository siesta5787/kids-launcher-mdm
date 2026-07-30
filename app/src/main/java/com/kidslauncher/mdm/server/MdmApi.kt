package com.kidslauncher.mdm.server

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.server.dto.EnrollResponse
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/** Shared JSON config for both Retrofit and the cached-policy preference blob. The server's JSON
 * is snake_case (Rust's serde default, no per-field renames); this maps it to/from idiomatic
 * camelCase Kotlin properties without needing an `@SerialName` on every field. */
@OptIn(ExperimentalSerializationApi::class)
val ServerJson: Json = Json {
    ignoreUnknownKeys = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

/**
 * Device-facing kid-phone-server REST endpoints. Enrollment is unauthenticated (the enrollment
 * code itself is the one-shot credential); policy/status require the bearer token issued at
 * enrollment, attached by [createMdmApi] via a header rather than threaded through every call.
 */
interface MdmApi {

    @POST("api/devices/enroll")
    suspend fun enroll(@Body request: EnrollRequest): Response<EnrollResponse>

    @GET("api/devices/policy")
    suspend fun getPolicy(): Response<PolicyResponse>

    @POST("api/devices/status")
    suspend fun sendStatus(@Body report: StatusReportRequest): Response<Unit>
}

/** [token] is omitted for the enroll-only call (no token exists yet); pass it for every
 * subsequent authenticated call. */
fun createMdmApi(baseUrl: String, token: String? = null): MdmApi {
    val clientBuilder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)

    if (token != null) {
        clientBuilder.addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            )
        }
    }

    val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    return Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(clientBuilder.build())
        .addConverterFactory(ServerJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MdmApi::class.java)
}
