package com.psphere.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "psphere_prefs")

class TokenDataStore(private val context: Context) {

    companion object {
        // Auth
        private val KEY_JWT          = stringPreferencesKey("jwt_token")
        private val KEY_DEVICE_ID    = stringPreferencesKey("device_id")
        private val KEY_DEVICE_TOKEN = stringPreferencesKey("device_token")
        private val KEY_P2P_MODE     = booleanPreferencesKey("p2p_mode")
        private val KEY_SERVER_URL   = stringPreferencesKey("server_url")

        // Sync settings
        val KEY_WIFI_ONLY          = booleanPreferencesKey("sync_wifi_only")
        val KEY_CHARGING_ONLY      = booleanPreferencesKey("sync_charging_only")
        val KEY_SYNC_FOLDERS       = stringPreferencesKey("sync_folders")
        val KEY_FREE_UP_SPACE      = booleanPreferencesKey("sync_free_space")
        val KEY_SYNC_INTERVAL_HOURS = longPreferencesKey("sync_interval_hours")
    }

    // ── Auth ──────────────────────────────────────────────────────────────────
    val jwtToken:    Flow<String?>  = context.dataStore.data.map { it[KEY_JWT] }
    val deviceId:    Flow<String?>  = context.dataStore.data.map { it[KEY_DEVICE_ID] }
    val deviceToken: Flow<String?>  = context.dataStore.data.map { it[KEY_DEVICE_TOKEN] }
    val p2pMode:     Flow<Boolean>  = context.dataStore.data.map { it[KEY_P2P_MODE] ?: false }
    val serverUrl:   Flow<String?>  = context.dataStore.data.map { it[KEY_SERVER_URL] }

    // ── Sync settings ─────────────────────────────────────────────────────────
    val wifiOnly:          Flow<Boolean>     = context.dataStore.data.map { it[KEY_WIFI_ONLY]     ?: true  }
    val chargingOnly:      Flow<Boolean>     = context.dataStore.data.map { it[KEY_CHARGING_ONLY] ?: false }
    val freeUpSpace:       Flow<Boolean>     = context.dataStore.data.map { it[KEY_FREE_UP_SPACE] ?: false }
    val syncIntervalHours: Flow<Long>        = context.dataStore.data.map { it[KEY_SYNC_INTERVAL_HOURS] ?: 6L }
    val syncFolders:       Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_SYNC_FOLDERS]
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    suspend fun saveJwt(token: String) =
        context.dataStore.edit { it[KEY_JWT] = token }

    suspend fun saveDevice(deviceId: String, deviceToken: String) =
        context.dataStore.edit {
            it[KEY_DEVICE_ID]    = deviceId
            it[KEY_DEVICE_TOKEN] = deviceToken
        }

    suspend fun saveServerUrl(url: String) =
        context.dataStore.edit { it[KEY_SERVER_URL] = url.trimEnd('/') }

    suspend fun enableP2pMode(localDeviceId: String) =
        context.dataStore.edit {
            it[KEY_P2P_MODE]  = true
            it[KEY_DEVICE_ID] = localDeviceId
            it.remove(KEY_JWT)
            it.remove(KEY_DEVICE_TOKEN)
            it.remove(KEY_SERVER_URL)
        }

    suspend fun signOut() =
        context.dataStore.edit {
            it.remove(KEY_JWT)
            it.remove(KEY_DEVICE_ID)
            it.remove(KEY_DEVICE_TOKEN)
            it.remove(KEY_SERVER_URL)
            it[KEY_P2P_MODE] = false
        }

    suspend fun setWifiOnly(v: Boolean)     = context.dataStore.edit { it[KEY_WIFI_ONLY]     = v }
    suspend fun setChargingOnly(v: Boolean) = context.dataStore.edit { it[KEY_CHARGING_ONLY] = v }
    suspend fun setFreeUpSpace(v: Boolean)  = context.dataStore.edit { it[KEY_FREE_UP_SPACE] = v }
    suspend fun setSyncIntervalHours(v: Long) = context.dataStore.edit { it[KEY_SYNC_INTERVAL_HOURS] = v }
    suspend fun setSyncFolders(paths: Set<String>) =
        context.dataStore.edit { it[KEY_SYNC_FOLDERS] = paths.joinToString(",") }

    suspend fun clear() = context.dataStore.edit { it.clear() }
}
