package com.kidslauncher.mdm.headwind

import com.kidslauncher.mdm.headwind.dto.EnrollRequest
import com.kidslauncher.mdm.headwind.dto.KidModePolicy
import com.kidslauncher.mdm.headwind.dto.KidModeStatusReport
import com.kidslauncher.mdm.headwind.dto.ServerConfig
import com.kidslauncher.mdm.headwind.dto.ServerEnvelope
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/** Shared JSON config for both Retrofit and the cached-policy preference blob. */
val HeadwindJson: Json = Json {
    ignoreUnknownKeys = true
}

/**
 * Device-facing Headwind MDM REST endpoints this client actually needs. No request-signature
 * header - the self-hosted dev server doesn't enforce `secure.enrollment`, and unauthenticated
 * calls were confirmed working directly against it.
 */
interface MdmApi {

    /** Enrolls (creating the device server-side if it doesn't exist yet) or re-fetches config. */
    @POST("rest/public/sync/configuration/{number}")
    suspend fun enroll(
        @Path("number") number: String,
        @Body request: EnrollRequest,
    ): Response<ServerEnvelope<ServerConfig>>

    @GET("rest/public/sync/configuration/{number}")
    suspend fun getConfig(@Path("number") number: String): Response<ServerEnvelope<ServerConfig>>

    @GET("rest/plugins/kidmode/policy/device/{number}")
    suspend fun getKidModePolicy(@Path("number") number: String): Response<ServerEnvelope<KidModePolicy>>

    @POST("rest/plugins/kidmode/status/device/{number}")
    suspend fun sendKidModeStatus(
        @Path("number") number: String,
        @Body report: KidModeStatusReport,
    ): Response<Unit>
}

fun createMdmApi(baseUrl: String): MdmApi {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    return Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(client)
        .addConverterFactory(HeadwindJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MdmApi::class.java)
}
