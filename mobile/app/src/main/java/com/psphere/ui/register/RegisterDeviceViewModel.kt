package com.psphere.ui.register

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psphere.data.TokenDataStore
import com.psphere.network.ApiClient
import com.psphere.network.DeviceRegisterRequest
import com.psphere.network.LoginRequest
import com.psphere.network.RegisterRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

sealed class RegisterState {
    object Idle           : RegisterState()
    object CheckingHealth : RegisterState()
    object Registering    : RegisterState()
    object Success        : RegisterState()
    data class Error(val message: String) : RegisterState()
}

class RegisterDeviceViewModel : ViewModel() {

    private val _state = MutableStateFlow<RegisterState>(RegisterState.Idle)
    val state: StateFlow<RegisterState> = _state

    val defaultDeviceName: String = Build.MODEL

    /**
     * Login to an existing account, then register this device.
     * Saves the server URL so ApiClient uses it on next launch.
     */
    fun login(
        context: Context,
        serverUrl: String,
        email: String,
        password: String,
        deviceName: String,
        deviceType: String,
    ) {
        if (serverUrl.isBlank() || email.isBlank() || password.isBlank() || deviceName.isBlank()) {
            _state.value = RegisterState.Error("All fields are required")
            return
        }
        viewModelScope.launch {
            val store = TokenDataStore(context)
            try {
                ApiClient.setBaseUrl(serverUrl)
                store.saveServerUrl(serverUrl)

                _state.value = RegisterState.CheckingHealth
                ApiClient.api.health()

                _state.value = RegisterState.Registering
                val loginResponse = ApiClient.api.login(LoginRequest(email, password))
                store.saveJwt(loginResponse.token)

                val deviceResponse = ApiClient.api.registerDevice(
                    bearer = "Bearer ${loginResponse.token}",
                    body   = DeviceRegisterRequest(name = deviceName, type = deviceType)
                )
                store.saveDevice(deviceResponse.deviceId, deviceResponse.token)

                _state.value = RegisterState.Success
            } catch (e: Exception) {
                _state.value = RegisterState.Error(friendlyError(e, serverUrl))
            }
        }
    }

    /**
     * Create a new account on the server, then register this device.
     */
    fun createAccount(
        context: Context,
        serverUrl: String,
        email: String,
        password: String,
        deviceName: String,
        deviceType: String,
    ) {
        if (serverUrl.isBlank() || email.isBlank() || password.isBlank() || deviceName.isBlank()) {
            _state.value = RegisterState.Error("All fields are required")
            return
        }
        viewModelScope.launch {
            val store = TokenDataStore(context)
            try {
                ApiClient.setBaseUrl(serverUrl)
                store.saveServerUrl(serverUrl)

                _state.value = RegisterState.CheckingHealth
                ApiClient.api.health()

                _state.value = RegisterState.Registering
                // Register account first, then login to get token
                ApiClient.api.register(RegisterRequest(email, password))
                val loginResponse = ApiClient.api.login(LoginRequest(email, password))
                store.saveJwt(loginResponse.token)

                val deviceResponse = ApiClient.api.registerDevice(
                    bearer = "Bearer ${loginResponse.token}",
                    body   = DeviceRegisterRequest(name = deviceName, type = deviceType)
                )
                store.saveDevice(deviceResponse.deviceId, deviceResponse.token)

                _state.value = RegisterState.Success
            } catch (e: Exception) {
                _state.value = RegisterState.Error(friendlyError(e, serverUrl))
            }
        }
    }

    /** Skip Core registration entirely and use P2P-only mode with a local device ID. */
    fun skipToP2p(context: Context) {
        viewModelScope.launch {
            val localId = UUID.randomUUID().toString()
            TokenDataStore(context).enableP2pMode(localId)
            _state.value = RegisterState.Success
        }
    }

    fun resetState() {
        _state.value = RegisterState.Idle
    }

    private fun friendlyError(e: Exception, serverUrl: String): String = when (e) {
        is SocketTimeoutException -> "Connection timed out. Is the backend running at $serverUrl?"
        is ConnectException,
        is UnknownHostException   -> "Cannot reach server. Check your network and the backend address."
        is HttpException          -> when (e.code()) {
            400  -> "Invalid request. Check your email/password format."
            401  -> "Invalid email or password."
            403  -> "Access denied."
            409  -> "An account with this email already exists. Try logging in."
            else -> "Server error (${e.code()}). Try again."
        }
        else -> e.message ?: "Registration failed"
    }
}
