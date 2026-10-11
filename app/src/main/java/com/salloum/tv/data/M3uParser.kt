package com.salloum.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

import java.io.BufferedReader
import java.io.InputStreamReader

object M3uParser {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    suspend fun parseFromUrl(url: String): List<Channel> = withContext(Dispatchers.IO) {
        val channels = mutableListOf<Channel>()
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "*/*")

            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                return@withContext channels
            }

            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                var currentName: String? = null
                var currentLogo: String? = null
                var currentCategory: String? = null

                reader.forEachLine { line ->
                    val trimmedLine = line.trim()
                    when {
                        trimmedLine.startsWith("#EXTINF:") -> {
                            currentName = trimmedLine.substringAfterLast(",").trim()
                            currentLogo = extractAttribute(trimmedLine, "tvg-logo")
                            currentCategory = extractAttribute(trimmedLine, "group-title")
                        }
                        trimmedLine.startsWith("http") && currentName != null -> {
                            channels.add(Channel(currentName!!, trimmedLine, currentLogo, currentCategory))
                            currentName = null
                            currentLogo = null
                            currentCategory = null
                        }
                    }
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            // Swallow: a single failing source must not break the whole load.
            // The caller aggregates results from multiple sources.
        }
        channels
    }

    private fun extractAttribute(line: String, attribute: String): String? {
        val key = "$attribute=\""
        if (!line.contains(key)) return null
        return line.substringAfter(key).substringBefore("\"")
    }
}