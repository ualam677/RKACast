package com.rkacast.cast.dlna

data class DlnaDevice(
    val usn: String,
    val friendlyName: String,
    val manufacturer: String,
    val modelName: String,
    val location: String,
    val avTransportControlUrl: String,
    val renderingControlUrl: String?,
)
