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

    /** 第一次调用时后台加载一次；已加载过则直接返回缓存 */
    fun ensureLoaded() {
        if (loaded || loading) return
        loading = true
        scope.launch {
            try {
                error = null
                // 第一批并行：libraries + resume + latest（各 10 秒超时）
                val libsDef = async { withTimeoutOrNull(10000) { Repo.libraries() } ?: emptyList() }
                val resumeDef = async { withTimeoutOrNull(10000) { try { Repo.resumeItems() } catch (_: Exception) { emptyList() } } ?: emptyList() }
                val latestDef = async { withTimeoutOrNull(10000) { try { Repo.latestItems(8) } catch (_: Exception) { emptyList() } } ?: emptyList() }
                val l = libsDef.await()
                libs = l
                resume = resumeDef.await()
                latest = latestDef.await()
                // 第二批并行：每个媒体库的 items（各 10 秒超时）
                val itemsDefs = l.map { lib ->
                    lib.id to async {
                        withTimeoutOrNull(10000) { try { Repo.items(lib.id, 12) } catch (_: Exception) { emptyList() } } ?: emptyList()
                    }
                }
                val map = mutableMapOf<String, List<UiMediaItem>>()
                for ((id, def) in itemsDefs) {
                    map[id] = def.await()
                    // 增量更新：每回来一个库就刷新 UI，不用等全部
                    libItems = map.toMap()
                }
                loaded = true
            } catch (e: Exception) {
                error = e.message ?: "未知错误"
            } finally {
                loading = false
            }
        }
    }

    /** 手动重试（仅出错时用） */
    fun retry() {
        loaded = false
        error = null
        libs = null
        resume = null
        latest = null
        libItems = emptyMap()
        ensureLoaded()
    }
}
