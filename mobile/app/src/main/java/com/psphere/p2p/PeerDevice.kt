package com.psphere.p2p

data class PeerDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int,
)
