package com.muse.gomepc.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** 校验本次加载的 key 是否仍是当前服务器；切服务器后旧请求的返回一律丢弃 */
    private fun keyStillValid(key: String): Boolean =
        key == serverKey && key == currentServerKey()

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
        // 先试磁盘缓存：有就秒开，后台再刷新（照抄安卓 DataCache 思路）
        val cachedLibs = HomeDiskCache.loadLibraries(key)
        if (cachedLibs != null) {
            libs = cachedLibs
            resume = HomeDiskCache.loadResume(key) ?: emptyList()
            latest = HomeDiskCache.loadLatest(key) ?: emptyList()
            val map = mutableMapOf<String, List<UiMediaItem>>()
            for (lib in cachedLibs) {
                HomeDiskCache.loadLibItems(key, lib.id)?.let { map[lib.id] = it }
            }
            libItems = map
            loaded = true
            // 后台刷新，不阻塞 UI
            refreshInBackground(key)
            return
        }
        // 无缓存，走网络首载
        loading = true
        scope.launch {
            loadFromNetwork(key)
        }
    }

    /** 后台刷新：拉新数据，更新 UI 并覆写磁盘缓存 */
    private fun refreshInBackground(key: String) {
        scope.launch {
            loadFromNetwork(key, isRefresh = true)
        }
    }

    /**
     * 单次网络加载。fetch 返回 null = 超时/失败，本次不覆盖旧数据（超时返回的空列表
     * 绝不能清空"继续观看"）；只有成功返回才赋值，且每次赋值前校验 key、切回 Main 线程。
     */
    private suspend fun loadFromNetwork(key: String, isRefresh: Boolean = false) {
        try {
            if (!isRefresh) withContext(Dispatchers.Main) { error = null }
            // 结构化并发：async 挂在本次 coroutineScope 下，随加载取消而取消，不泄漏
            val firstBatch = coroutineScope {
                // 失败/超时返回 null；CancellationException 必须重抛，不能被吞掉
                val libsDef = async {
                    try { withTimeoutOrNull(15000) { Repo.libraries() } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
                val resumeDef = async {
                    try { withTimeoutOrNull(15000) { Repo.resumeItems() } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
                val latestDef = async {
                    try { withTimeoutOrNull(15000) { Repo.latestItems(8) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
                Triple(libsDef.await(), resumeDef.await(), latestDef.await())
            }
            if (!keyStillValid(key)) return
            val l = firstBatch.first
            if (!l.isNullOrEmpty()) {
                withContext(Dispatchers.Main) { libs = l }
                HomeDiskCache.saveLibraries(key, l)
            } else if (!isRefresh) {
                // 首载且媒体库为空：大概率是网络失败，不要标记 loaded（否则 UI 一直空列表无错误）
                withContext(Dispatchers.Main) {
                    error = "加载失败，请检查网络或服务器"
                    loading = false
                }
                return
            }
            firstBatch.second?.let { r ->
                withContext(Dispatchers.Main) { resume = r }
                HomeDiskCache.saveResume(key, r)
            }
            firstBatch.third?.let { lat ->
                withContext(Dispatchers.Main) { latest = lat }
                HomeDiskCache.saveLatest(key, lat)
            }
            // 库列表出来就标记完成，UI 先显示；剧集后台并行填
            withContext(Dispatchers.Main) {
                loaded = true
                if (!isRefresh) loading = false
            }
            // 第二批：每个媒体库的 items（各 15 秒超时，并行，增量更新 UI）
            val libsForItems = l ?: emptyList()
            coroutineScope {
                val itemsDefs = libsForItems.map { lib ->
                    lib.id to async {
                        try { withTimeoutOrNull(15000) { Repo.items(lib.id, 12, libName = lib.name) } }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                    }
                }
                for ((id, def) in itemsDefs) {
                    val items = def.await() ?: continue // 超时/失败：跳过，不覆盖旧数据
                    if (!keyStillValid(key)) return@coroutineScope
                    if (items.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            libItems = libItems.toMutableMap().also { it[id] = items }
                        }
                        HomeDiskCache.saveLibItems(key, id, items)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isRefresh) withContext(Dispatchers.Main) { error = e.message ?: "未知错误" }
        } finally {
            if (!isRefresh) withContext(Dispatchers.Main) { loading = false }
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

    /** 只刷新"继续观看"一行，不碰首页其它数据（播放器退出后调用） */
    fun refreshResumeOnly() {
        val key = currentServerKey()
        scope.launch {
            try {
                val data = Repo.resumeItems()
                // 切服务器后返回的旧数据丢弃；状态赋值切 Main 线程
                if (key != currentServerKey()) return@launch
                withContext(Dispatchers.Main) {
                    resume = data
                }
                HomeDiskCache.saveResume(key, data)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }
}
