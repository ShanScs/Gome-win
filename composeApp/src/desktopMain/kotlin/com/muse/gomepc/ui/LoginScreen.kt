package com.muse.gomepc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardActions
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.gomepc.emby.Prefs
import com.muse.gomepc.emby.YambyClient
import kotlinx.coroutines.launch

/**
 * 服务器登录页。
 * 输入服务器地址（支持 http(s)://host:port/path 完整格式）、用户名、密码，
 * 调用 YambyClient.login()。成功后 Prefs 持久化（token/userId/服务器信息）。
 * 提供"演示模式"入口（无服务器时用 MockData）。
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onDemoMode: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // 从 Prefs 预填上次的服务器
    var serverUrl by remember { mutableStateOf(Prefs.baseUrl().takeIf { Prefs.host.isNotEmpty() } ?: "") }
    var username by remember { mutableStateOf(Prefs.username) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 密码可见切换（之前无，输错只能删了重输）
    var passwordVisible by remember { mutableStateOf(false) }

    fun parseServerUrl(input: String): Boolean {
        val s = input.trim()
        if (s.isEmpty()) return false
        return try {
            // 支持 "http://host:port/path" / "https://host:port" / "host:port" / "host"
            // 先全部解析到局部变量并校验，全部通过后才写 Prefs（校验失败时不留下半写配置）
            var rest = s
            var proto = "http"
            if (rest.startsWith("http://", true)) {
                proto = "http"; rest = rest.substring(7)
            } else if (rest.startsWith("https://", true)) {
                proto = "https"; rest = rest.substring(8)
            }
            val slashIdx = rest.indexOf('/')
            val hostPort = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
            val pathPart = if (slashIdx >= 0) rest.substring(slashIdx + 1) else ""
            val colonIdx = hostPort.lastIndexOf(':')
            val host = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
            val portStr = if (colonIdx >= 0) hostPort.substring(colonIdx + 1) else
                if (proto == "https") "443" else "80"
            // 校验：host 非空、无空白/斜杠；端口必须为 1..65535 的数字
            if (host.isBlank() || host.any { it.isWhitespace() || it == '/' }) return false
            val portNum = portStr.toIntOrNull() ?: return false
            if (portNum !in 1..65535) return false
            val path = pathPart.trim('/').ifEmpty { "emby" }
            if (path.any { it.isWhitespace() }) return false
            Prefs.protocol = proto
            Prefs.host = host
            Prefs.port = portNum.toString()
            Prefs.path = path
            true
        } catch (_: Exception) {
            false
        }
    }

    fun doLogin() {
        if (busy) return
        error = null
        if (!parseServerUrl(serverUrl)) {
            error = "服务器地址格式不对（例：http://192.168.1.10:8096/emby）"
            return
        }
        if (username.isBlank()) {
            error = "请输入用户名"
            return
        }
        busy = true
        scope.launch {
            try {
                YambyClient.login(username.trim(), password)
                // 登录成功：记录服务器到列表
                try {
                    Prefs.rememberCurrentServer(password)
                    Prefs.touchServerLastUsed(Prefs.currentServerKey())
                } catch (_: Exception) { }
                busy = false
                // 成功后清空密码，不让明文密码留在内存/输入框里
                password = ""
                onLoggedIn()
            } catch (e: Exception) {
                busy = false
                error = "登录失败：${e.message?.take(120)}"
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(GomeTheme.Bg),
        contentAlignment = Alignment.Center
    ) {
        MGlassBox(Modifier.width(420.dp), corner = 20.dp) {
            Column(
                Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Gome", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = GomeTheme.TextPrimary)
                Text(
                    "连接 Emby 服务器",
                    fontSize = 14.sp,
                    color = GomeTheme.TextSecondary
                )
                Spacer(Modifier.height(4.dp))

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; error = null },
                    label = { Text("服务器地址") },
                    placeholder = { Text("http://192.168.1.10:8096/emby") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("密码") },
                    singleLine = true,
                    // 回车直接登录（之前密码框按回车没反应）
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { doLogin() }),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Text(
                            if (passwordVisible) "隐藏" else "显示",
                            fontSize = 13.sp,
                            color = GomeTheme.Accent,
                            modifier = Modifier.clickable { passwordVisible = !passwordVisible }
                                .padding(8.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                )

                // 已保存服务器快捷选择：点一下填地址 + 用户名（之前每次都要手动输全地址）
                val savedServers = remember { Prefs.getServers() }
                if (savedServers.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("快速选择已保存服务器：", fontSize = 12.sp, color = GomeTheme.TextSecondary)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            savedServers.forEach { entry ->
                                AssistChip(
                                    onClick = {
                                        serverUrl = "${entry.protocol}://${entry.host}:${entry.port}/${entry.path.trim('/')}"
                                        username = entry.username
                                        error = null
                                    },
                                    label = { Text(entry.name.ifEmpty { entry.host }, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }

                if (error != null) {
                    Text(
                        error!!,
                        fontSize = 13.sp,
                        color = Color(0xFFE53935),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Button(
                    onClick = { doLogin() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.width(20.dp).height(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("连接中…", fontSize = 15.sp)
                    } else {
                        Text("登录", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("没有服务器？", fontSize = 13.sp, color = GomeTheme.TextSecondary)
                    TextButton(onClick = onDemoMode, enabled = !busy) {
                        Text("进入演示模式", fontSize = 13.sp, color = GomeTheme.Accent)
                    }
                }
            }
        }
    }
}
