package com.muse.gomepc.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 主页数据单例缓存：只在第一次启动时后台加载一次，之后切回主页直接用缓存，不再刷新。
 */
object HomeDataCache {
    var libs by mutableStateOf<List<UiLibrary>?>(null)
        private set
    var libItems by mutableStateOf<Map<String, List<UiMediaItem>>>(emptyMap())
        private set
    var resume by mutableStateOf<List<UiMediaItem>?>(null)
        private set
    var latest by mutableStateOf<List<UiMediaItem>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loading = false
    private var serverKey = ""

    private fun currentServerKey(): String {
        val p = com.muse.gomepc.emby.Prefs
        return "${p.protocol}://${p.host}:${p.port}${p.path}|${p.username}"
    }

    /** 第一次调用时后台加载一次；已加载过则直接返回缓存；服务器变了则重载 */
    fun ensureLoaded() {
        val key = currentServerKey()
        if (key != serverKey) {
            // 服务器变了，清空重载
            serverKey = key
            loaded = false
            loading = false
            error = null
            libs = null
            resume = null
            latest = null
            libItems = emptyMap()
        }
        if (loaded || loading) return
        loading = true
        scope.launch {
            try {
                error = null
                // 第一批：libraries + resume + latest（各 15 秒超时，并行）
                val libsDef = async { withTimeoutOrNull(15000) { Repo.libraries() } ?: emptyList() }
                val resumeDef = async { withTimeoutOrNull(15000) { try { Repo.resumeItems() } catch (_: Exception) { emptyList() } } ?: emptyList() }
                val latestDef = async { withTimeoutOrNull(15000) { try { Repo.latestItems(8) } catch (_: Exception) { emptyList() } } ?: emptyList() }
                val l = libsDef.await()
                libs = l
                resume = resumeDef.await()
                latest = latestDef.await()
                // 库列表出来就标记完成，UI 先显示；剧集后台并行填
                loaded = true
                // 第二批：每个媒体库的 items（各 15 秒超时，并行，增量更新 UI）
                val itemsDefs = l.map { lib ->
                    lib.id to async {
                        withTimeoutOrNull(15000) { try { Repo.items(lib.id, 12) } catch (_: Exception) { emptyList() } } ?: emptyList()
                    }
                }
                val map = mutableMapOf<String, List<UiMediaItem>>()
                for ((id, def) in itemsDefs) {
                    map[id] = def.await()
                    // 增量更新：每回来一个库就刷新 UI，不用等全部
                    libItems = map.toMap()
                }
            } catch (e: Exception) {
                error = e.message ?: "未知错误"
            } finally {
                loading = false
            }
        }
    }

    /** 手动重试（仅出错时用） */
    fun retry() {
        serverKey = currentServerKey()
        loaded = false
        loading = false
        error = null
        libs = null
        resume = null
        latest = null
        libItems = emptyMap()
        ensureLoaded()
    }
}
