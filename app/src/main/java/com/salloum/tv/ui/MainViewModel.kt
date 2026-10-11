package com.salloum.tv.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.salloum.tv.data.Channel
import com.salloum.tv.data.M3uParser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _allChannels = MutableStateFlow<List<Channel>>(emptyList())
    private val _categories = MutableStateFlow<Map<String, List<Channel>>>(emptyMap())
    val categories: StateFlow<Map<String, List<Channel>>> = _categories

    private val _recentChannels = MutableStateFlow<List<Channel>>(loadRecent())
    val recentChannels: StateFlow<List<Channel>> = _recentChannels

    private val _favorites = MutableStateFlow<Set<String>>(loadFavorites())
    val favorites: StateFlow<Set<String>> = _favorites

    private val _selectedCategory = MutableStateFlow(prefs.getString("last_category", "Sports") ?: "Sports")
    val selectedCategory: StateFlow<String> = _selectedCategory

    private val _selectedChannel = MutableStateFlow<Channel?>(null)
    val selectedChannel: StateFlow<Channel?> = _selectedChannel

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        // Show cached channels immediately (if any), then refresh in background.
        val cached = loadCachedChannels()
        if (cached.isNotEmpty()) {
            _allChannels.value = cached
            processCategories(cached)
        }
        loadChannels()
    }

    private fun loadFavorites(): Set<String> {
        return prefs.getStringSet("favorites", emptySet()) ?: emptySet()
    }

    private fun loadRecent(): List<Channel> {
        val json = prefs.getString("recent_channels", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Channel>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadCachedChannels(): List<Channel> {
        val json = prefs.getString("cached_channels", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Channel>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCachedChannels(channels: List<Channel>) {
        prefs.edit()
            .putString("cached_channels", gson.toJson(channels))
            .putLong("cached_channels_time", System.currentTimeMillis())
            .apply()
    }

    private fun saveRecent(channel: Channel) {
        val current = _recentChannels.value.toMutableList()
        current.removeAll { it.url == channel.url }
        current.add(0, channel)
        val updated = current.take(5)
        _recentChannels.value = updated
        prefs.edit().putString("recent_channels", gson.toJson(updated)).apply()
        processCategories(_allChannels.value)
    }

    fun toggleFavorite(channelUrl: String) {
        val current = _favorites.value.toMutableSet()
        if (current.contains(channelUrl)) {
            current.remove(channelUrl)
        } else {
            current.add(channelUrl)
        }
        _favorites.value = current
        prefs.edit().putStringSet("favorites", current).apply()
        processCategories(_allChannels.value)
    }

    fun loadChannels() {
        viewModelScope.launch {
            _isLoading.value = true

            val sources = listOf(
                "https://iptv-org.github.io/iptv/languages/ara.m3u",
                "https://iptv-org.github.io/iptv/categories/sports.m3u",
                "https://iptv-org.github.io/iptv/countries/iq.m3u",
                "https://iptv-org.github.io/iptv/countries/sa.m3u",
                "https://iptv-org.github.io/iptv/countries/qa.m3u",
                "https://raw.githubusercontent.com/Free-IPTV/Countries/master/Arabic.m3u",
                "https://raw.githubusercontent.com/Moira-S/IPTV-Free/refs/heads/main/sports.m3u"
            )
            val allFetchedChannels = mutableListOf<Channel>()

            withContext(Dispatchers.IO) {
                sources.map { url ->
                    async {
                        try {
                            M3uParser.parseFromUrl(url)
                        } catch (e: Exception) {
                            emptyList<Channel>()
                        }
                    }
                }.awaitAll().forEach {
                    allFetchedChannels.addAll(it)
                }
            }

            // Only overwrite state and cache if we actually got something back.
            if (allFetchedChannels.isNotEmpty()) {
                _allChannels.value = allFetchedChannels
                saveCachedChannels(allFetchedChannels)
                processCategories(allFetchedChannels)
            } else {
                _isLoading.value = false
            }
        }
    }

    private fun processCategories(channels: List<Channel>) {
        viewModelScope.launch(Dispatchers.Default) {
            val categorized = mutableMapOf<String, List<Channel>>()

            // 0. Recent Channels (First if available)
            if (_recentChannels.value.isNotEmpty()) {
                categorized["Recent"] = _recentChannels.value
            }

            // 1. Manual Backup & Specified Channels (High Quality & More Stable)
            val manualChannels = listOf(
                // Entertainment & Kids (High Quality)
                Channel("قناة دراغون بول", "https://fast.spacetoongo.com/channels/8bc1fd94-c5cc-40a9-b174-4ba6eb44684b/master6000000.m3u8?session=8bc1fd94-c5cc-40a9-b174-4ba6eb44684b"),
                Channel("قناة كونان", "https://fast.spacetoongo.com/channels/ba2f82fb-62ab-4139-ae62-7aa9c37638ee/master6000000.m3u8?session=ba2f82fb-62ab-4139-ae62-7aa9c37638ee"),
                Channel("Cartoon Network", "https://cdn4.skygo.mn/live/disk1/Cartoon_Network/HLSv3-FTA/Cartoon_Network-avc1_3000000=12-mp4a_256000_eng=6.m3u8"),
                Channel("الشرقية (Al Sharqiya)", "https://5d94523502c2d.streamlock.net/alsharqiyalive/mystream/chunklist_w132655001.m3u8?v=50617c9d"),
                Channel("دجلة (Dijlah)", "https://ghaasiflu.online/Dijlah/tracks-v1a1/mono.m3u8?v=50617c9d"),
                Channel("سامراء (Samara)", "https://live.bradosti.net/live/samaraweb_1080/index.m3u8"),
                Channel("UTV (يو تي في)", "https://utviraqi-live.lg.mncdn.com/utviraqi2/64c80359/chunklist.m3u8"),
                
                // New Arabic Sports Channels (2024)
                Channel("Al Iraqia Sport", "https://imn-live.esite-lab.com/hls/iraqia-sports-1.m3u8"),
                Channel("Arryadia", "http://149.100.11.252:8000/play/a065/index.m3u8"),
                Channel("Bahrain Sports 1", "https://5c7b683162943.streamlock.net/live/ngrp:sportsone_all/playlist.m3u8"),
                Channel("Bahrain Sports 2", "https://5c7b683162943.streamlock.net/live/ngrp:bahrainsportstwo_all/playlist.m3u8"),
                Channel("Jordan Sport", "https://jrtv-live.ercdn.net/jordansporthd/jordansporthd.m3u8"),
                Channel("Oman Sports TV", "https://partneta.cdn.mgmlcdn.com/omsport/smil:omsport.stream.smil/chunklist.m3u8"),
                Channel("Sharjah Sports", "https://svs.itworkscdn.net/smc4sportslive/smc4tv.smil/playlist.m3u8"),
                Channel("beIN SPORTS XTRA", "https://bein-xtra-bein.amagi.tv/playlist.m3u8"),
                Channel("beIN SPORTS XTRA en Espanol", "http://201.190.41.246:9060/play/a03y/index.m3u8"),
                Channel("Multivision Sports", "https://stream.digitalgt.com:3605/live/multivisionsportslive.m3u8"),

                // Alwan Sport Channels (قنوات ألوان رياضية)
                Channel("ألوان سبورت 1 (Alwan Sport 1)", "https://kurdtv.org/api/public/hls?u=https://prod-fastly-us-west-2.video.pscp.tv/Transcoding/v1/hls/7sVz2sS9uNLdYCAJJQPrv9US1ZogmBN3Dtxe8HB_CyN_w3yZ8wpi_xzdYzX_l4M1r0pyuuv_8st7P5C5Q274pQ/transcode/us-west-2/periscope-replay-direct-prod-us-west-2-public/eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCIsInZlcnNpb24iOiIyIn0.eyJFbmNvZGVyU2V0dGluZyI6ImVuY29kZXJfc2V0dGluZ183MjBwMzBfMTAiLCJIZWlnaHQiOjcyMCwiS2JwcyI6Mjc1MCwiV2lkdGgiOjEyODB9.ldktM4fCFRfkP4ZEBfZPKtlAUNAcTPkoz994YJAzWpE/dynamic_delta.m3u8?type=live"),
                Channel("ألوان سبورت 2 (Alwan Sport 2)", "https://kurdtv.org/api/public/hls?u=https://prod-fastly-us-west-2.video.pscp.tv/Transcoding/v1/hls/Kz5z-UYz7HFDokCRb5qW4HDDegyJlhGhnb8ElAfGdl0wmrD5eRTP0J5kBeSb2UAcl65pw2577XoYNLB2BQc55A/transcode/us-west-2/periscope-replay-direct-prod-us-west-2-public/eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCIsInZlcnNpb24iOiIyIn0.eyJFbmNvZGVyU2V0dGluZyI6ImVuY29kZXJfc2V0dGluZ183MjBwMzBfMTAiLCJIZWlnaHQiOjcyMCwiS2JwcyI6Mjc1MCwiV2lkdGgiOjEyODB9.ldktM4fCFRfkP4ZEBfZPKtlAUNAcTPkoz994YJAzWpE/dynamic_delta.m3u8?type=live"),
                Channel("ألوان سبورت 3 (Alwan Sport 3)", "https://kurdtv.org/api/public/hls?u=https://prod-fastly-us-west-2.video.pscp.tv/Transcoding/v1/hls/C0mcuWHxf8Kf0DN0dn_8d-GgWX13M3-L4G1d8uVtbe4s76UnfPMc3Sd030wG4YVRoHZ2KWaKu9BXrzrfqIecFA/transcode/us-west-2/periscope-replay-direct-prod-us-west-2-public/eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCIsInZlcnNpb24iOiIyIn0.eyJFbmNvZGVyU2V0dGluZyI6ImVuY29kZXJfc2V0dGluZ183MjBwMzBfMTAiLCJIZWlnaHQiOjcyMCwiS2JwcyI6Mjc1MCwiV2lkdGgiOjEyODB9.ldktM4fCFRfkP4ZEBfZPKtlAUNAcTPkoz994YJAzWpE/dynamic_delta.m3u8?type=live"),
                Channel("ألوان سبورت 4 (Alwan Sport 4)", "https://kurdtv.org/api/public/hls?u=https%3A%2F%2Fprod-fastly-us-west-2.video.pscp.tv%2FTranscoding%2Fv1%2Fhls%2F3B5fjU0dobiOtR9-BdR7Y0JVedY3hp3bj0RnHejus8Ve7QLvO5N9qY-wlfyfVg6r9SR77WgW5LFfVjb1kJ9Fgg%2Ftranscode%2Fus-west-2%2Fperiscope-replay-direct-prod-us-west-2-public%2FeyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCIsInZlcnNpb24iOiIyIn0.eyJFbmNvZGVyU2V0dGluZyI6ImVuY29kZXJfc2V0dGluZ183MjBwMzBfMTAiLCJIZWlnaHQiOjcyMCwiS2JwcyI6Mjc1MCwiV2lkdGgiOjEyODB9.ldktM4fCFRfkP4ZEBfZPKtlAUNAcTPkoz994YJAzWpE%2Fdynamic_delta.m3u8%3Ftype%3Dlive"),
                Channel("ألوان سبورت 5 (Alwan Sport 5)", "https://ameditv.ai.studio/api/proxy?url=https://prod-fastly-ap-south-1.video.pscp.tv/Transcoding/v1/hls/SIaTkhd-IIW8W0zKAlS5_NlF1jq6a1EO628q6oMR71BdeUudmXzd48cO5bdSLFaR_fXrg7Ij6GATGIaYSZPa3w/transcode/ap-southeast-1/periscope-replay-direct-prod-ap-southeast-1-public/eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCIsInZlcnNpb24iOiIyIn0.eyJFbmNvZGVyU2V0dGluZyI6ImVuY29kZXJfc2V0dGluZ183MjBwMzBfMTAiLCJIZWlnaHQiOjcyMCwiS2JwcyI6Mjc1MCwiV2lkdGgiOjEyODB9.ldktM4fCFRfkP4ZEBfZPKtlAUNAcTPkoz994YJAzWpE/dynamic_delta.m3u8?type=live")
            )

            // 2. Optimized Filtering
            val pool = (manualChannels + channels)
                .distinctBy { it.url }
                .filter { ch ->
                    ch.url.isNotEmpty() && !ch.name.contains("error", true) && !ch.name.contains("offline", true)
                }
                .toMutableList()

            fun claim(keywords: List<String>): List<Channel> {
                val matched = pool.filter { ch ->
                    val name = ch.name.lowercase()
                    keywords.any { name.contains(it.lowercase()) }
                }
                pool.removeAll(matched)
                return matched
            }

            // 3. Precise Categorization (Order matters!)
            categorized["MBC"] = claim(listOf("MBC"))
            categorized["Rotana"] = claim(listOf("Rotana", "روتانا"))
            categorized["Kids"] = claim(listOf("كونان", "دراغون بول", "كارتون", "Kids", "Cartoon", "Dragon", "Baraem", "براعم", "Spacetoon"))

            // New Categories for 2026
            categorized["Documentaries"] = claim(listOf("الوثائقية", "National Geographic", "Nat Geo", "Documentary", "Wild", "Discovery", "History", "Al Jazeera World"))
            categorized["DramaSeries"] = claim(listOf("Drama", "Series", "دراما", "مسلسلات", "Zay Alwan", "زي ألوان", "Bollywood", "بوليود", "Mix", "Bab Al Hara", "باب الحارة"))

            categorized["MoviesSeries"] = claim(listOf("ifilm", "Hollywood", "Cinema", "Movies", "أفلام"))

            // Move Sports sections BEFORE News to catch sports-news channels
            // Global Sports: Focusing on Premier League, LaLiga, Serie A, and Champions League
            categorized["GlobalSports"] = claim(listOf(
                "Sky Sport", "TNT Sport", "BT Sport", "DAZN", "Movistar", "Eleven",
                "Premier League", "LaLiga", "Serie A", "Champions League", "BeIN Sports"
            ))

            // Alwan Sport Channels Category
            categorized["AlwanSport"] = claim(listOf("ألوان سبورت", "Alwan Sport"))

            // Arabic Sports
            val sportsCandidates = claim(listOf("Sport", "Match", "رياضة", "KSA Sport", "AD Sport", "Dubai Sport", "Yalla", "Libya", "ليبيا", "Alkass", "ON Time", "Arryadia", "الكأس", "الرياضية", "Al Iraqia Sport", "Bahrain Sports", "Jordan Sport", "Oman Sports", "Sharjah Sports", "Multivision Sports"))
            categorized["Sports"] = sportsCandidates.filter { ch ->
                val name = ch.name
                val lower = name.lowercase()
                val hasArabicScript = name.any { it in '\u0600'..'\u06FF' }
                val arabicKeywords = listOf(
                    "ad sport", "abu dhabi sport", "ksa sport", "saudi sport",
                    "dubai sport", "alkass", "al kass", "arryadia", "arryadiya",
                    "kuwait sport", "oman sport", "bahrain sport", "iraq sport",
                    "iraqi sport", "sharjah sport", "yalla", "ontime", "on sport",
                    "time sport", "jordan sport", "libya sport", "sbc sport",
                    "al iraqia sport", "bahrain sports", "jordan sport", "oman sports",
                    "sharjah sports", "multivision sports"
                )
                hasArabicScript || arabicKeywords.any { lower.contains(it) }
            }

            categorized["Iraqi"] = claim(listOf("Sharqiya", "الشرقية", "UTV", "يو تي في", "Dijlah", "دجلة", "Iraq", "العراق", "Samara", "سامراء"))
            categorized["News"] = claim(listOf("News", "Arabiya", "Hadath", "Jazeera", "الجزيرة", "الحدث", "العربية", "RT", "Sky News", "روسيا اليوم", "الإخبارية"))
            categorized["Religious"] = claim(listOf("Quran", "القرآن", "Sunnah", "السنة", "Saudi", "Alkafeel", "الكفيل", "Imam Hussein", "الامام الحسين"))

            withContext(Dispatchers.Main) {
                _categories.value = categorized
                _isLoading.value = false
            }
        }
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
        prefs.edit().putString("last_category", category).apply()
    }

    fun selectChannel(channel: Channel) {
        _selectedChannel.value = channel
        saveRecent(channel)
        prefs.edit().putString("last_channel_url", channel.url).apply()
    }
}