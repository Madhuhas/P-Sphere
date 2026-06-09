package com.psphere.network

import com.psphere.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                else HttpLoggingInterceptor.Level.NONE
    }

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(logging)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // Active base URL — starts from BuildConfig, can be overridden at runtime
    @Volatile private var _baseUrl: String = BuildConfig.BASE_URL
    @Volatile private var _api: ApiService? = null

    val api: ApiService
        get() = _api ?: buildApi(_baseUrl).also { _api = it }

    /** Call after the user configures a new server URL. Rebuilds the Retrofit instance. */
    fun setBaseUrl(url: String) {
        val cleaned = url.trimEnd('/')
        if (cleaned != _baseUrl) {
            _baseUrl = cleaned
            _api = null
        }
    }

    fun currentBaseUrl(): String = _baseUrl

    private fun buildApi(baseUrl: String): ApiService =
        Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
}
