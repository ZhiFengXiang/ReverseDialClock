# ========================================================================
# 反转表盘时钟 - Android APK 构建脚本
# ========================================================================
#
# 功能：使用 Android SDK 命令行工具手动构建 APK（无需 Gradle）
#
# 构建流程：
#   1. aapt2 compile  — 编译 res/ 下的资源文件为扁平格式
#   2. aapt2 link     — 链接资源 + 清单文件 + assets，生成 R.java 和基础 APK
#   3. javac          — 编译 Java 源码（含生成的 R.java）为 .class
#   4. d8             — 将 .class 转换为 Android DEX 字节码
#   5. aapt add       — 将 DEX 文件注入 APK（保持正确的 APK 结构）
#   6. keytool        — 生成 RSA-4096 签名密钥库
#   7. zipalign       — 4 字节对齐优化（含页面级对齐）
#   8. apksigner      — v1+v2+v3 多方案签名
#
# 依赖：
#   - JDK 25+（javac、keytool）
#   - Android SDK build-tools 37.0.0
#   - Android SDK platforms android-36.1
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File build.ps1
#
# 输出：
#   项目根目录下的 clock.apk
# ========================================================================

$ErrorActionPreference = "Stop"

# --- 环境配置 ---
# 指定 JDK 路径（确保使用可用的 JDK 版本）
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot"

# Android SDK 路径和工具路径
$sdk = "C:\Users\Xzf13\AppData\Local\Android\Sdk"
$bt = "$sdk\build-tools\37.0.0"                           # 构建工具版本
$platform = "$sdk\platforms\android-36.1\android.jar"     # 目标平台
$project = "c:\Users\Xzf13\Desktop\clock\1.8\android"       # 项目根目录
$build = "$project\build"                                  # 构建输出目录
$keystore = "$project\release.keystore"                   # 签名密钥稳定路径（独立于 $build，构建清理时不删除，支持 APK 原地覆盖更新）

# --- 清理构建目录 ---
# 清理前先保全既有签名密钥：若稳定密钥不存在但构建目录中残留旧密钥，则迁移复用（保持签名一致以支持覆盖更新）
if (-not (Test-Path $keystore) -and (Test-Path "$build\release.keystore")) {
    Copy-Item "$build\release.keystore" $keystore -Force
}
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force -Path "$build\gen", "$build\obj", "$build\apk", "$build\dex" | Out-Null

# --- Step 1: 编译资源文件 ---
# aapt2 compile 将 res/ 下的 XML/PNG 等资源编译为 Android 扁平格式（.flat）
# 输出为 resources.zip（包含所有 .flat 文件）
Write-Host "=== Step 1: Compile resources with aapt2 ===" -ForegroundColor Cyan
& "$bt\aapt2.exe" compile --dir "$project\res" -o "$build\resources.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# --- Step 2: 链接资源 ---
# aapt2 link 将编译后的资源与 AndroidManifest.xml 链接
# -I: 引用 android.jar 中的系统资源
# --java: 生成 R.java 到指定目录
# -A: 将 assets 目录打包进 APK（关键：包含 index.html 时钟页面）
# --auto-add-overlay: 允许资源覆盖
Write-Host "=== Step 2: Link resources (with assets) ===" -ForegroundColor Cyan
& "$bt\aapt2.exe" link `
    -I "$platform" `
    --manifest "$project\AndroidManifest.xml" `
    --java "$build\gen" `
    --auto-add-overlay `
    -A "$project\assets" `
    -o "$build\apk\app.base.apk" `
    "$build\resources.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# --- Step 3: 编译 Java 源码 ---
# javac 编译 src/ 下的 Java 文件和 aapt2 生成的 R.java
# --release 17: 目标 Java 17 字节码（兼容 Android 8.0+）
Write-Host "=== Step 3: Compile Java ===" -ForegroundColor Cyan
$javaFiles = Get-ChildItem -Path "$project\src" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
$genFiles = Get-ChildItem -Path "$build\gen" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
& javac `
    --release 17 `
    -classpath "$platform" `
    -d "$build\obj" `
    $javaFiles $genFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# --- Step 4: 转换为 DEX 字节码 ---
# d8 将 Java .class 文件转换为 Android DEX 格式
# --min-api 21: 确保生成的 DEX 兼容 Android 5.0+
# 注意：输出目录必须预先创建，否则 d8 报错
Write-Host "=== Step 4: Convert to DEX ===" -ForegroundColor Cyan
$classFiles = Get-ChildItem -Path "$build\obj" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
& "$bt\d8.bat" `
    --lib "$platform" `
    --min-api 21 `
    --output "$build\dex" `
    $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# --- Step 5: 将 DEX 注入 APK ---
# 使用 aapt add 将 classes.dex 添加到 APK 中
# Push-Location 切换到 dex 目录，确保 aapt 使用正确的相对路径
# 这比手动 zip 操作更可靠，能保持 APK 内部结构完整
Write-Host "=== Step 5: Add DEX to APK using aapt ===" -ForegroundColor Cyan
Copy-Item "$build\apk\app.base.apk" "$build\apk\app.unsigned.apk"
Push-Location "$build\dex"
& "$bt\aapt.exe" add -f "$build\apk\app.unsigned.apk" classes.dex 2>&1 | Out-Null
Pop-Location
# 验证 DEX 是否成功添加
$dexCheck = & "$bt\aapt.exe" list "$build\apk\app.unsigned.apk" 2>&1 | Select-String "classes.dex"
if (-not $dexCheck) { throw "Failed to add DEX to APK" }

# --- Step 6: 确保签名密钥存在（仅在缺失时生成，跨构建复用同一密钥以支持覆盖更新） ---
# 使用 RSA-4096 算法生成密钥，有效期 100 年
# SHA256withRSA 签名算法，确保现代设备兼容
Write-Host "=== Step 6: Ensure release keystore ===" -ForegroundColor Cyan
if (-not (Test-Path $keystore)) {
    & keytool -genkeypair `
        -v `
        -keystore $keystore `
        -storepass clockapp2024 `
        -alias clockapp `
        -keypass clockapp2024 `
        -keyalg RSA `
        -keysize 4096 `
        -validity 36500 `
        -dname "CN=Clock App,OU=Dev,O=Clock,L=Beijing,ST=Beijing,C=CN" `
        -sigalg SHA256withRSA
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
} else {
    Write-Host "Reusing existing keystore: $keystore" -ForegroundColor DarkGray
}

# --- Step 7: 字节对齐优化 ---
# zipalign 对 APK 内的未压缩数据进行 4 字节对齐
# -p: 启用页面级对齐（Android 15+ 16KB 页面大小兼容）
# -f: 覆盖已存在的输出文件
Write-Host "=== Step 7: Align APK ===" -ForegroundColor Cyan
& "$bt\zipalign.exe" -f -p 4 "$build\apk\app.unsigned.apk" "$build\apk\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# --- Step 8: APK 签名 ---
# apksigner 使用 v1+v2+v3 三种签名方案同时签名
# v1 (JAR): 兼容旧设备
# v2 (APK Signature Scheme): Android 7.0+ 验证更快
# v3: Android 9.0+ 支持密钥轮换
Write-Host "=== Step 8: Sign APK (v1+v2+v3) ===" -ForegroundColor Cyan
& "$bt\apksigner.bat" sign `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --v3-signing-enabled true `
    --ks $keystore `
    --ks-pass pass:clockapp2024 `
    --ks-key-alias clockapp `
    --key-pass pass:clockapp2024 `
    --out "$build\apk\clock.apk" `
    "$build\apk\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

# --- 输出最终 APK ---
Copy-Item "$build\apk\clock.apk" "c:\Users\Xzf13\Desktop\clock\1.8\clock.apk"

Write-Host ""
Write-Host "=== BUILD SUCCESS ===" -ForegroundColor Green
Write-Host "APK: c:\Users\Xzf13\Desktop\clock\1.8\clock.apk" -ForegroundColor Green
$size = (Get-Item "c:\Users\Xzf13\Desktop\clock\1.8\clock.apk").Length / 1KB
Write-Host "Size: $([math]::Round($size, 1)) KB" -ForegroundColor Green
