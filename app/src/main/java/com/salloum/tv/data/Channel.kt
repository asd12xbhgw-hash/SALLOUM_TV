package com.salloum.tv.data

data class Channel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val category: String? = null
)
