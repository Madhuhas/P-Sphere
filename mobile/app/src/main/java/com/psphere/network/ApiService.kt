package com.psphere.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

// ── Auth ──────────────────────────────────────────────────────────────────────

data class RegisterRequest(val email: String, val password: String)
data class LoginRequest(val email: String, val password: String)
data class LoginResponse(val token: String, val user: UserDto)
data class UserDto(val id: String, val email: String)

// ── Devices ───────────────────────────────────────────────────────────────────

data class DeviceRegisterRequest(val name: String, val type: String)
data class DeviceRegisterResponse(val deviceId: String, val token: String)

// ── Health ────────────────────────────────────────────────────────────────────

data class HealthResponse(val status: String, val timestamp: String)

// ── Sync ──────────────────────────────────────────────────────────────────────

data class SyncCheckRequest(val deviceToken: String, val hashes: List<String>)
data class SyncCheckResponse(val missing: List<String>)
data class UploadResponse(val fileId: String)

// ── Photos ────────────────────────────────────────────────────────────────────

data class PhotoDto(
    val id: String,
    val taken_at: String?,
    val thumb_url: String,
    val width: Int?,
    val height: Int?,
    val camera: String?,
)
data class PaginationDto(val page: Int, val limit: Int, val total: Int, val pages: Int)
data class TimelineResponse(val photos: List<PhotoDto>, val pagination: PaginationDto)

// ── Albums ────────────────────────────────────────────────────────────────────

data class AlbumDto(
    val id: String,
    val name: String,
    val type: String,
    val created_at: String?,
    val photo_count: Int,
    val cover_url: String?,
)
data class AlbumsResponse(val albums: List<AlbumDto>)
data class AlbumDetailResponse(val album: AlbumDto, val photos: List<PhotoDto>)

// ── AI ────────────────────────────────────────────────────────────────────────

data class AiSearchRequest(val query: String, val limit: Int = 20)
data class AiSearchResultItem(val id: String, val taken_at: String?, val city: String?, val country: String?, val tags: List<String>, val people: List<String>, val thumb_url: String, val score: Double)
data class AiSearchResponse(val photos: List<AiSearchResultItem>, val total: Int, val explanation: String, val fallback: Boolean)

data class SummaryDateRange(val earliest: String?, val latest: String?)
data class AiSummaryResponse(val summary: String, val photoCount: Int, val dateRange: SummaryDateRange, val topLocations: List<String>, val topTags: List<String>)

data class CleanupSuggestion(val photoId: String, val reason: String, val thumb_url: String)
data class AiCleanupResponse(val suggestions: List<CleanupSuggestion>, val summary: String)

// ── Stats ─────────────────────────────────────────────────────────────────────

data class StatsResponse(val totalPhotos: Int, val totalFiles: Int, val totalSizeBytes: Long, val deviceCount: Int)

// ── Interface ─────────────────────────────────────────────────────────────────

interface ApiService {

    // Health
    @GET("api/health")
    suspend fun health(): HealthResponse

    // Auth
    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): UserDto

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    // Devices
    @POST("api/devices/register")
    suspend fun registerDevice(
        @Header("Authorization") bearer: String,
        @Body body: DeviceRegisterRequest
    ): DeviceRegisterResponse

    // Sync
    @POST("api/sync/check")
    suspend fun syncCheck(
        @Header("Authorization") bearer: String,
        @Body body: SyncCheckRequest
    ): SyncCheckResponse

    @Multipart
    @POST("api/sync/upload")
    suspend fun uploadFile(
        @Header("Authorization") bearer: String,
        @Part file: MultipartBody.Part,
        @Part("deviceToken") deviceToken: RequestBody,
        @Part("path") filePath: RequestBody,
        @Part("hash") hash: RequestBody,
        @Part("modifiedAt") modifiedAt: RequestBody,
    ): UploadResponse

    // Photos
    @GET("api/photos/timeline")
    suspend fun getTimeline(
        @Header("Authorization") bearer: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
    ): TimelineResponse

    // Albums
    @GET("api/albums")
    suspend fun getAlbums(@Header("Authorization") bearer: String): AlbumsResponse

    @GET("api/albums/{id}")
    suspend fun getAlbum(
        @Header("Authorization") bearer: String,
        @Path("id") albumId: String,
    ): AlbumDetailResponse

    @DELETE("api/albums/{id}")
    suspend fun deleteAlbum(
        @Header("Authorization") bearer: String,
        @Path("id") albumId: String,
    )

    @POST("api/albums/smart/generate")
    suspend fun generateSmartAlbums(@Header("Authorization") bearer: String): Any

    // AI
    @POST("api/ai/search")
    suspend fun aiSearch(
        @Header("Authorization") bearer: String,
        @Body body: AiSearchRequest,
    ): AiSearchResponse

    @GET("api/ai/summary")
    suspend fun aiSummary(@Header("Authorization") bearer: String): AiSummaryResponse

    @GET("api/ai/cleanup")
    suspend fun aiCleanup(@Header("Authorization") bearer: String): AiCleanupResponse

    // Stats
    @GET("api/stats")
    suspend fun getStats(@Header("Authorization") bearer: String): StatsResponse
}
