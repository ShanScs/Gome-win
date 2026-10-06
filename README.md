# Gome PC

Gome 的桌面版（Emby 客户端），杜比视界兼容播放。

## 技术栈

- **UI**：Compose Multiplatform Desktop（Kotlin）
- **播放器**：libmpv 单引擎（JNA 绑定，`wid` 原生窗口嵌入）
  - `vo=gpu-next` + libplacebo，杜比视界 RPU → HDR10
  - Windows：`hwdec=d3d11va`；Linux：`hwdec=auto`
- **Emby 对接**：YambyClient（REST + JSON，与 Android 版同源）
- **弹幕**：自研 DanmakuEngine（Compose Canvas 绘制）

## 杜比视界说明

PC 端无原生 Dolby Vision 输出链（微软授权限制）。
本应用解析 DV 动态元数据（RPU）→ 输出 HDR10 信号，
对外称为"杜比视界兼容"，不承诺原生 DV。

## 构建

需要 Java 17+。

```bash
# Linux/macOS
./gradlew :composeApp:run

# Windows 安装包（需在 Windows 上运行）
./gradlew :composeApp:packageExe -PtargetFormat=Exe
```

### Windows libmpv

从 [shinchiro/mpv-winbuild-cmake](https://github.com/shinchiro/mpv-winbuild-cmake/releases)
下载 `mpv-dev-x86_64-*.7z`，解压 `libmpv-2.dll` 到应用目录。
该构建带 libdovi（已验证含 `apply_dolbyvision`）。

GitHub Actions 会自动下载并打包（见 `.github/workflows/build-windows.yml`）。

## 运行

- 首次启动显示登录页，输入 Emby 服务器地址 + 账号密码
- 演示模式：`./bin/gome-pc -Dui.demo=true`（无需服务器）
- 配置保存在 `~/.java/.userPrefs/com/muse/gomepc`（Linux）
  / 注册表 `HKCU\Software\JavaSoft\Prefs\com\muse\gomepc`（Windows）

## 许可证

© 2026 Muse
