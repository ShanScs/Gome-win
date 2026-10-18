package com.muse.gomepc.emby

import org.json.JSONObject

/**
 * 杜比视界检测（从 Android 版 DolbyVision.kt 移植的纯 Kotlin 部分）。
 *
 * PC 端注意：检测逻辑与 Android 一致，但路由不同——PC 用 libmpv 单引擎
 * 处理所有 DV Profile（P5 经 RPU reshape，P8 动态元数据→HDR10，P7 取 HDR10 基带层），
 * 不需要 ExoPlayer。所以这里只保留 profileFromPlaybackInfo，不做 useExoPlayer 路由。
 *
 * 检测原则（P0-E11）：只认显式证据，不默认 P5。
 * - DvProfile 字段 / CodecTag 对应的 Codec 里的 "dvhe.05" 这类显式 profile 数字才返回 profile；
 * - VideoRangeType=DOVI 但无具体 profile 时返回 null（未知），不再默认 P5；
 * - VideoDoViTitle 用词边界匹配 "DV"/"Dolby Vision"，避免 "Advanced"/"Adventure" 误标，
 *   且标题里无 profile 数字时同样返回 null，不默认 P5。
 */
object DolbyVision {

    /** 从 PlaybackInfo JSON 的 MediaSources/MediaStreams 里提取 DV Profile，null 表示未检出 */
    fun profileFromPlaybackInfo(json: JSONObject, mediaSourceId: String = ""): Int? {
        try {
            val sources = json.optJSONArray("MediaSources") ?: return null
            for (si in 0 until sources.length()) {
                val src = sources.optJSONObject(si) ?: continue
                if (mediaSourceId.isNotEmpty() && src.optString("Id") != mediaSourceId) continue
                val streams = src.optJSONArray("MediaStreams") ?: continue
                for (i in 0 until streams.length()) {
                    val st = streams.optJSONObject(i) ?: continue
                    if (st.optString("Type") != "Video") continue

                    // 1. DvProfile 字段（最直接、最可信）
                    val dvProfile = st.optInt("DvProfile", -1)
                    if (dvProfile in 1..10) return dvProfile

                    // 2. VideoRangeType == "DOVI"：仅证明是 DV 流，无具体 profile。
                    //    不再默认返回 5，继续找其他显式证据；最终无证据则返回 null。
                    //    （旧代码无条件按 P5 处理，是误标来源之一）

                    // 3. CodecTag: dvhe / dvh1 / dva1 / dvav
                    //    从 Codec 里用词边界提取显式 profile，如 "dvhe.05.06" -> 5；无数字则不默认。
                    val tag = st.optString("CodecTag", "").lowercase()
                    if (tag in setOf("dvhe", "dvh1", "dva1", "dvav")) {
                        val codec = st.optString("Codec", "").lowercase()
                        val m = Regex("""\bdv\w*\.0?(\d{1,2})\b""").find(codec)
                        val p = m?.groupValues?.get(1)?.toIntOrNull()
                        if (p != null && p in 1..10) return p
                    }

                    // 4. VideoDoViTitle：词边界匹配 "DV" / "Dolby Vision"，
                    //    避免 "Advanced"/"Adventure" 等含 "dv" 子串的标题误标；
                    //    标题含 DV 但无 profile 数字时不默认 P5。
                    val title = st.optString("VideoDoViTitle", "")
                    if (Regex("""\b(dolby\s+vision|dv)\b""", RegexOption.IGNORE_CASE).containsMatchIn(title)) {
                        val m = Regex("""\bprofile\s*0?(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(title)
                        val p = m?.groupValues?.get(1)?.toIntOrNull()
                        if (p != null && p in 1..10) return p
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
