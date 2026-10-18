package com.muse.gomepc.emby

import org.json.JSONObject

/**
 * 杜比视界检测（从 Android 版 DolbyVision.kt 移植的纯 Kotlin 部分）。
 *
 * PC 端注意：检测逻辑与 Android 一致，但路由不同——PC 用 libmpv 单引擎
 * 处理所有 DV Profile（P5 经 RPU reshape，P8 动态元数据→HDR10，P7 取 HDR10 基带层），
 * 不需要 ExoPlayer。所以这里只保留 profileFromPlaybackInfo，不做 useExoPlayer 路由。
 */
object DolbyVision {

    /** 从 PlaybackInfo JSON 的 MediaSources/MediaStreams 里提取 DV Profile，null 表示未检出 */
    fun profileFromPlaybackInfo(json: JSONObject): Int? {
        try {
            val sources = json.optJSONArray("MediaSources") ?: return null
            for (si in 0 until sources.length()) {
                val src = sources.getJSONObject(si)
                val streams = src.optJSONArray("MediaStreams") ?: continue
                for (i in 0 until streams.length()) {
                    val st = streams.getJSONObject(i)
                    if (st.optString("Type") != "Video") continue

                    // 1. DvProfile 字段（最直接）
                    val dvProfile = st.optInt("DvProfile", -1)
                    if (dvProfile in 1..10) return dvProfile

                    // 2. VideoRangeType == "DOVI"
                    if (st.optString("VideoRangeType").equals("DOVI", true)) {
                        return 5 // 有 DOVI 但无具体 profile，保守按 P5 处理
                    }

                    // 3. CodecTag: dvhe / dvh1 / dva1 / dvav
                    val tag = st.optString("CodecTag", "").lowercase()
                    if (tag in listOf("dvhe", "dvh1", "dva1", "dvav")) {
                        val codec = st.optString("Codec", "").lowercase()
                        val m = Regex("""dv\w*\.0?(\d)""").find(codec)
                        return m?.groupValues?.get(1)?.toIntOrNull() ?: 5
                    }

                    // 4. VideoDoViTitle 包含 "DV"
                    val title = st.optString("VideoDoViTitle", "")
                    if (title.contains("DV", true)) {
                        val m = Regex("""profile\s*0?(\d)""", RegexOption.IGNORE_CASE).find(title)
                        return m?.groupValues?.get(1)?.toIntOrNull() ?: 5
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
