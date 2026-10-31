package com.psphere.network

import android.content.Context
import coil.ImageLoader
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Loads server photos. Every Core route, thumbnails included, requires a JWT, but Coil's
 * default loader sends no headers, so in server mode no thumbnail ever loaded. This loader
 * adds the token to requests going to the configured server, and nowhere else.
 */
object ServerImages {

    /** Latest JWT, kept in sync from DataStore by PSphereApp. Null in P2P mode. */
    @Volatile var jwt: String? = null

    /** Server paths like "/api/photos/thumb/<id>/sm" → full URL on the current server. */
    fun absolute(pathOrUrl: String?): String? = when {
        pathOrUrl.isNullOrBlank() -> null
        pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") ||
            pathOrUrl.startsWith("content://") || pathOrUrl.startsWith("file://") -> pathOrUrl
        else -> ApiClient.currentBaseUrl().trimEnd('/') + "/" + pathOrUrl.trimStart('/')
    }

    /** Full-size version of a server thumbnail URL. Local content:// URIs are returned unchanged. */
    fun large(pathOrUrl: String?): String? {
        val url = absolute(pathOrUrl) ?: return null
        return if (url.startsWith("content://") || url.startsWith("file://")) url
               else url.replace(Regex("/sm$"), "/lg")
    }

    private val authInterceptor = Interceptor { chain ->
        val req = chain.request()
        val token = jwt
        val server = ApiClient.currentBaseUrl().toHttpUrlOrNull()
        val sameServer = server != null && req.url.host == server.host && req.url.port == server.port
        if (token != null && sameServer && req.header("Authorization") == null) {
            chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
        } else {
            chain.proceed(req)
        }
    }

    fun imageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor(authInterceptor)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
}
