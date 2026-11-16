package com.rkacast.remote

import kotlinx.serialization.Serializable

@Serializable
data class SamsungTv(
    val ip: String,
    val name: String,
    val model: String = "",
    val mac: String? = null,
    val tokenAuth: Boolean = true,
)
