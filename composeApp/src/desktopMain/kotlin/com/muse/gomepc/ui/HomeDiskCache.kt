package com.muse.gomepc.ui

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 主页数据磁盘缓存：媒体库列表、继续观看、最新、每个库的条目。
 * 按服务器 key 隔离。策略：进主页先显示缓存（秒开），后台拉新数据后更新 UI 并覆写缓存。
 * 照抄安卓 DataCache.kt 的思路。
 */
object HomeDiskCache {

    private fun baseDir(): File {
        val home = System.getProperty("user.home") ?: "."
        return File(home, ".gome/home_cache").apply { mkdirs() }
    }

    private fun dir(serverKey: String): File {
        val safe = serverKey.replace(Regex("[^a-zA-Z0-9]"), "_").take(48).ifEmpty { "default" }
        return File(baseDir(), safe).apply { mkdirs() }
    }

    fun clear(serverKey: String) {
        try { dir(serverKey).deleteRecursively() } catch (_: Exception) {}
    }

    // ---------- UiLibrary ----------

    fun saveLibraries(serverKey: String, libs: List<UiLibrary>) {
        try {
            val arr = JSONArray()
            libs.forEach { l ->
                arr.put(JSONObject().put("id", l.id).put("name", l.name).put("hue", l.hue.toDouble()))
            }
            File(dir(serverKey), "libraries.json").writeText(arr.toString())
        } catch (_: Exception) {}
    }

    fun loadLibraries(serverKey: String): List<UiLibrary>? {
        return try {
            val f = File(dir(serverKey), "libraries.json")
            if (!f.exists()) return null
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UiLibrary(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    hue = o.optDouble("hue", 210.0).toFloat()
                )
            }.filter { it.id.isNotEmpty() }.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    // ---------- UiMediaItem ----------

    private fun itemToJson(it: UiMediaItem): JSONObject =
        JSONObject()
            .put("id", it.id)
            .put("name", it.name)
            .put("year", it.year)
            .put("rating", it.rating ?: "")
            .put("overview", it.overview)
            .put("libName", it.libName)
            .put("imageUrl", it.imageUrl ?: "")
            .put("backdropUrl", it.backdropUrl ?: "")
            .put("hue", it.hue.toDouble())
            .put("progress", it.progress?.toDouble() ?: -1.0)
            .put("badge", it.badge ?: "")
            .put("subtitle", it.subtitle)
            .put("episodeCount", it.episodeCount)
            .put("isFavorite", it.isFavorite)
            .put("type", it.type)
            .put("played", it.played)
            .put("genres", JSONArray(it.genres))

    private fun itemFromJson(o: JSONObject): UiMediaItem =
        UiMediaItem(
            id = o.optString("id"),
            name = o.optString("name"),
            year = o.optString("year"),
            rating = o.optString("rating").ifEmpty { null },
            overview = o.optString("overview"),
            libName = o.optString("libName"),
            imageUrl = o.optString("imageUrl").ifEmpty { null },
            backdropUrl = o.optString("backdropUrl").ifEmpty { null },
            hue = o.optDouble("hue", 210.0).toFloat(),
            progress = o.optDouble("progress", -1.0).let { if (it < 0) null else it.toFloat() },
            badge = o.optString("badge").ifEmpty { null },
            subtitle = o.optString("subtitle"),
            episodeCount = o.optInt("episodeCount"),
            isFavorite = o.optBoolean("isFavorite"),
            type = o.optString("type"),
            played = o.optBoolean("played"),
            genres = try {
                val arr = o.optJSONArray("genres")
                if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
            } catch (_: Exception) { emptyList() }
        )

    private fun saveItems(serverKey: String, filename: String, items: List<UiMediaItem>) {
        try {
            val arr = JSONArray()
            items.forEach { arr.put(itemToJson(it)) }
            File(dir(serverKey), filename).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    private fun loadItems(serverKey: String, filename: String): List<UiMediaItem>? {
        return try {
            val f = File(dir(serverKey), filename)
            if (!f.exists()) return null
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { itemFromJson(arr.getJSONObject(it)) }.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    fun saveResume(serverKey: String, items: List<UiMediaItem>) = saveItems(serverKey, "resume.json", items)
    fun loadResume(serverKey: String): List<UiMediaItem>? = loadItems(serverKey, "resume.json")

    fun saveLatest(serverKey: String, items: List<UiMediaItem>) = saveItems(serverKey, "latest.json", items)
    fun loadLatest(serverKey: String): List<UiMediaItem>? = loadItems(serverKey, "latest.json")

    fun saveLibItems(serverKey: String, libId: String, items: List<UiMediaItem>) {
        try {
            val safeId = libId.replace(Regex("[^a-zA-Z0-9]"), "_").take(32)
            saveItems(serverKey, "lib_$safeId.json", items)
        } catch (_: Exception) {}
    }

    fun loadLibItems(serverKey: String, libId: String): List<UiMediaItem>? {
        return try {
            val safeId = libId.replace(Regex("[^a-zA-Z0-9]"), "_").take(32)
            loadItems(serverKey, "lib_$safeId.json")
        } catch (_: Exception) { null }
    }
}
