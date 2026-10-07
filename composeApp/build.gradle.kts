import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.6.11"
}

// 锁定 androidx.collection 1.4.0（Compose 1.6.11 编译期版本，避免
// LongSparseArrayKt.access$getDELETED NoSuchMethodError）
configurations.all {
    resolutionStrategy {
        force("androidx.collection:collection-jvm:1.4.0")
        force("androidx.collection:collection:1.4.0")
    }
    // collection-ktx:1.2.0 自带旧版 LongSparseArrayKt（无 access$getDELETED），
    // 与 collection-jvm:1.4.0 的 LongSparseArray 混用导致 NoSuchMethodError，直接排除
    exclude(group = "androidx.collection", module = "collection-ktx")
}

kotlin {
    jvm("desktop") {
        withJava()
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.ui:ui-desktop:1.6.11")
                implementation("org.jetbrains.compose.foundation:foundation-desktop:1.6.11")
                implementation("org.jetbrains.compose.material3:material3-desktop:1.6.11")
                implementation("org.jetbrains.compose.components:components-resources:1.6.11")
                // 平台相关 Skiko（Linux 用 linux-x64，Windows CI 用 windows-x64）
                // 注意：compose.desktop.currentOs 在某些环境 Provider 解析异常，故用显式坐标 + os 检测
                val isWin = System.getProperty("os.name").lowercase().contains("win")
                if (isWin) {
                    implementation("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.6.11")
                    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.4")
                } else {
                    implementation("org.jetbrains.compose.desktop:desktop-jvm-linux-x64:1.6.11")
                    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.4")
                }
                // OkHttp（YambyClient 用，桌面 JVM 可用）
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                // JSON
                implementation("org.json:json:20240303")
                // JNA（libmpv 绑定用）
                implementation("net.java.dev.jna:jna:5.14.0")
                implementation("net.java.dev.jna:jna-platform:5.6.0")
                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
                // Workaround: lifecycle-viewmodel-android AAR transform broken in 离线环境,
                // add extracted classes.jar directly (see /tmp/aar-fix/).
                // 仅当 workaround 文件存在时启用（GitHub Actions 等正常环境走标准 AAR 解析）。
                val aarFix = file("/tmp/aar-fix/lifecycle-viewmodel-android-2.8.0-classes.jar")
                if (aarFix.isFile) implementation(files(aarFix))
            }
        }
    }
}

// 冒烟测试：运行 YambyClient 纯函数/DV检测/Prefs 验证（不连网）
// 用法：./gradlew :composeApp:runEmbySmokeTest
tasks.register<JavaExec>("runEmbySmokeTest") {
    group = "verification"
    description = "Run YambyClient smoke test (no network)"
    mainClass.set("com.muse.gomepc.emby.EmbySmokeTestKt")
    val desktopCompilation = kotlin.targets.getByName("desktop")
        .let { it as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget }
        .compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles
}

// libmpv JNA 冒烟测试：wid 窗口嵌入 + 播放（需 Xvfb + DISPLAY）
// 用法：DISPLAY=:99 ./gradlew :composeApp:runMpvSmokeTest
tasks.register<JavaExec>("runMpvSmokeTest") {
    group = "verification"
    description = "Run libmpv JNA + wid embedding smoke test (needs Xvfb)"
    mainClass.set("com.muse.gomepc.player.MpvSmokeTestKt")
    val desktopCompilation = kotlin.targets.getByName("desktop")
        .let { it as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget }
        .compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles
    // X11Util 的 peer 反射兜底需要
    jvmArgs("--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED")
}

// 弹幕覆盖层测试：Compose 窗口 + 滚动弹幕 + Robot 截图验证（需 Xvfb）
// 用法：DISPLAY=:99 ./gradlew :composeApp:runDanmakuTest
tasks.register<JavaExec>("runDanmakuTest") {
    group = "verification"
    description = "Run danmaku overlay test with screenshot verification (needs Xvfb)"
    mainClass.set("com.muse.gomepc.danmaku.DanmakuTestKt")
    val desktopCompilation = kotlin.targets.getByName("desktop")
        .let { it as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget }
        .compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles
}

// Canvas wid 验证：Frame 里的 Canvas 取 native id 给 mpv（需 Xvfb）
// 用法：DISPLAY=:99 ./gradlew :composeApp:runCanvasWidTest
tasks.register<JavaExec>("runCanvasWidTest") {
    group = "verification"
    description = "Verify mpv renders into AWT Canvas via wid (needs Xvfb)"
    mainClass.set("com.muse.gomepc.player.CanvasWidTestKt")
    val desktopCompilation = kotlin.targets.getByName("desktop")
        .let { it as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget }
        .compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles
    jvmArgs("--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED")
    jvmArgs("--add-opens", "java.desktop/java.awt=ALL-UNNAMED")
}

// UI 截图验证：Xvfb 下打开界面并 Robot 截图（需 Xvfb）
// 用法：DISPLAY=:99 ./gradlew :composeApp:runUiShot -Dui.screen=home|detail|player
tasks.register<JavaExec>("runUiShot") {
    group = "verification"
    description = "Run UI demo and take screenshot (needs Xvfb)"
    mainClass.set("com.muse.gomepc.UiScreenshotKt")
    val desktopCompilation = kotlin.targets.getByName("desktop")
        .let { it as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget }
        .compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles
    // X11Util 的 peer 反射兜底需要（Canvas 非 Window 组件取 native id）
    jvmArgs("--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED")
    jvmArgs("--add-opens", "java.desktop/java.awt=ALL-UNNAMED")
    // 透传截图参数
    systemProperty("ui.screen", System.getProperty("ui.screen", "home"))
    systemProperty("ui.video", System.getProperty("ui.video", "/tmp/dvtest/dv_p81.mp4"))
    systemProperty("ui.loop", System.getProperty("ui.loop", "false"))
    systemProperty("ui.demo", System.getProperty("ui.demo", "true"))
}

tasks.register<JavaExec>("runEmbyFlowTest") {
    group = "verification"
    description = "Run Emby real-flow test against mock server"
    mainClass.set("com.muse.gomepc.emby.EmbyFlowTestKt")
    val desktopCompilation = kotlin.targets.getByName("desktop")
        .let { it as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget }
        .compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles
    jvmArgs("--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED")
    jvmArgs("--add-opens", "java.desktop/java.awt=ALL-UNNAMED")
}


// Desktop application packaging
compose.desktop {
    application {
        mainClass = "com.muse.gomepc.MainKt"
        // Windows HWND 反射需要（Win32Util 取 Canvas 的 peer.getHWnd()）；
        // Linux 下无害（对应包不存在时忽略）。
        jvmArgs(
            "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.awt.windows=ALL-UNNAMED",
        )
        nativeDistributions {
            // targetFormats 按平台启用：Linux 本地构建用 app-image+zip（deb bundler 在此环境损坏）；
            // GitHub Actions Windows 构建时通过 -PtargetFormat=Exe 传入。
            // （Gradle 无法在配置期可靠判断 runner OS，故用 project property 开关）
            val fmtProp = findProperty("targetFormat")?.toString()?.lowercase()
            when (fmtProp) {
                "exe" -> targetFormats(TargetFormat.Exe)
                "msi" -> targetFormats(TargetFormat.Msi)
                "deb" -> targetFormats(TargetFormat.Deb)
                "dmg" -> targetFormats(TargetFormat.Dmg)
                "appimage", "app-image" -> targetFormats(TargetFormat.AppImage)
                // 默认：app-image 便携目录（Windows CI 用）
                else -> targetFormats(TargetFormat.AppImage)
            }
            packageName = "Gome"
            packageVersion = "1.0.64"
            description = "Gome PC — Emby client (Dolby Vision compatible)"
            copyright = "© 2026 Muse"
            linux {
                // iconFile.set(project.file("src/desktopMain/resources/icon.png"))
            }
            windows {
                // iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
            }
            macOS {
                // iconFile.set(project.file("src/desktopMain/resources/icon.icns"))
                bundleID = "com.muse.gomepc"
            }
        }
    }
}

// 发行包：手动组装（jpackage deb 在此环境损坏）
// 布局：gome-pc-1.0.0/{bin/gome-pc, lib/*.jar, README.txt} → tar.gz
// 用法：./gradlew :composeApp:collectDist
tasks.register("collectDist") {
    group = "distribution"
    description = "Assemble portable tar.gz distribution (libs + launcher script)"
    val desktopCompilation = kotlin.targets.getByName("desktop")
        .let { it as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget }
        .compilations.getByName("main")
    dependsOn("desktopJar")
    val distDir = layout.buildDirectory.dir("dist/gome-pc-1.0.0")
    val libDir = distDir.map { it.dir("lib") }
    val binDir = distDir.map { it.dir("bin") }
    inputs.files(desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles)
    outputs.dir(distDir)
    doLast {
        val lib = libDir.get().asFile.apply { mkdirs() }
        val bin = binDir.get().asFile.apply { mkdirs() }
        val cp = desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles
        val seen = mutableSetOf<String>()
        cp.files.forEach { f ->
            if (f.isFile && f.extension == "jar") {
                // 同名 jar 去重（保留第一个，避免 files() 依赖的重复 classes.jar 覆盖）
                if (seen.add(f.name)) f.copyTo(lib.resolve(f.name), overwrite = true)
            } else if (f.isDirectory) {
                // 编译输出目录：打包成 classes.jar
                val jarFile = lib.resolve("gome-pc-classes.jar")
                if (!seen.add(jarFile.name)) return@forEach
                ant.withGroovyBuilder {
                    "jar"("destfile" to jarFile.absolutePath, "basedir" to f.absolutePath)
                }
            }
        }
        val launcher = bin.resolve("gome-pc")
        launcher.writeText(
            """#!/bin/bash
# Gome PC launcher
DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="${'$'}JAVA_HOME/bin/java"
if [ ! -x "${'$'}JAVA_BIN" ]; then JAVA_BIN="java"; fi
# libmpv: 优先系统库；如自带 lib/ 下有 .so 则优先
if [ -d "${'$'}DIR/lib/native" ]; then export LD_LIBRARY_PATH="${'$'}DIR/lib/native:${'$'}LD_LIBRARY_PATH"; fi
# JVM 参数（-D/-X/--add-opens）放 main class 前，其余放后
JVM_ARGS="--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED --add-opens java.desktop/java.awt=ALL-UNNAMED -Dfile.encoding=UTF-8"
APP_ARGS=""
for a in "$@"; do
  case "${'$'}a" in
    -D*|-X*|--add-opens*) JVM_ARGS="${'$'}JVM_ARGS ${'$'}a" ;;
    *) APP_ARGS="${'$'}APP_ARGS ${'$'}a" ;;
  esac
done
exec ${'$'}JAVA_BIN ${'$'}JVM_ARGS -cp "${'$'}DIR/lib/*" com.muse.gomepc.MainKt ${'$'}APP_ARGS
"""
        )
        launcher.setExecutable(true)
        // README
        distDir.get().asFile.resolve("README.txt").writeText(
            """Gome PC 1.0.0 — Emby 客户端（杜比视界兼容）
========================================
依赖：
  - Java 17+（需设置 JAVA_HOME，或保证 java 在 PATH 中）
  - libmpv（系统库）：sudo apt install libmpv2   （Ubuntu/Debian）
                      或 sudo dnf install mpv-libs（Fedora）
  - X11 显示环境（Wayland 下用 XWayland 即可）

运行：
  ./bin/gome-pc
  演示模式：./bin/gome-pc -Dui.demo=true   （无需服务器，直接看 UI）
  指定测试视频：-Dui.video=/path/to/video.mp4

说明：
  - 首次启动显示登录页，输入 Emby 服务器地址+账号密码
  - 杜比视界：经 libmpv(libplacebo) 解析 RPU → 输出 HDR10（"兼容"非原生 DV）
  - 配置保存在 ~/.java/.userPrefs/com/muse/gomepc
"""
        )
        // tar.gz
        val tarFile = layout.buildDirectory.file("dist/gome-pc-1.0.0.tar.gz").get().asFile
        ant.withGroovyBuilder {
            "tar"("destfile" to tarFile.absolutePath, "compression" to "gzip") {
                "tarfileset"("dir" to layout.buildDirectory.dir("dist").get().asFile.absolutePath, "mode" to "755") {
                    "include"("name" to "gome-pc-1.0.0/bin/gome-pc")
                }
                "tarfileset"("dir" to layout.buildDirectory.dir("dist").get().asFile.absolutePath) {
                    "include"("name" to "gome-pc-1.0.0/**")
                    "exclude"("name" to "gome-pc-1.0.0/bin/gome-pc")
                }
            }
        }
        println("DIST: ${tarFile.absolutePath} (${tarFile.length() / 1024 / 1024} MB)")
    }
}
