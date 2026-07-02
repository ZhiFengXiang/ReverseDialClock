# 反转表盘时钟

一个独特的反向旋转时钟 — **指针固定指向 12 点方向，时/分/秒三个刻度环反向旋转指示时间**。
采用新拟态（Neumorphism）设计风格，白天淡蓝 INS 拟态、黑夜深色模式，根据当前时间自动切换主题。

同时提供 **网页版**（单 HTML 文件）和 **Android 应用**（含桌面小组件）。

支持添加多时区世界城市时钟，可与本地时间并排对比，主题（白天/黑夜）随当前显示时区的昼夜自动切换。

---

## ✨ 功能特性

### 核心设计
- **反转表盘**：指针固定，环旋转 — 时间刻度转至指针位置即表示当前时间
- **网络时间同步**：自动通过 HTTP Date 头校准时间，确保走时准确
- **流畅动画**：秒/分/时环连续平滑旋转，启动时有缓动入场动画

### 多时区世界时钟
- **底部拟态"+"按钮**：界面底部中央新增新拟态凸起按钮，材质与整体统一，白天/黑夜主题适配
- **无极变形城市卡片**：点击"+"按钮，按钮本身通过 CSS transition 平滑过渡尺寸/圆角/阴影，无极变形为圆角矩形城市选择卡片，"+"图标旋转 45° 变为"×"
- **11 个主要城市选项**：北京（UTC+8）、东京（UTC+9）、悉尼（UTC+10）、迪拜（UTC+4）、莫斯科（UTC+3）、新德里（UTC+5:30）、雅加达（UTC+7）、巴黎（UTC+1）、伦敦（UTC+0）、纽约（UTC-5）、洛杉矶（UTC-8）。点击城市立即添加该时区时钟并切换。不处理夏令时，使用标准固定偏移
- **最多 6 个时钟**：1 个本地默认时钟 + 最多 5 个添加时钟，达上限时"+"按钮自动隐藏
- **点式进度条**：时钟总数 ≥ 2 时，时钟下方出现点式进度条，当前时钟对应点高亮（更长、更亮），点数等于时钟总数
- **进度条丝滑切换**：进度条切换采用丝滑过渡（仅切换 active 类，不重建 DOM），120Hz 下无跳变
- **左右滑动切换**：在时钟区域水平滑动（>50px）切换相邻时钟，非线性缓动动画（easeInOutCubic，800ms），背景与时钟丝滑过渡（透明度 + 缩放），进度条同步更新
- **点击点切换**：点击进度条上任意点可直接切换至对应时钟
- **时区时间计算**：使用 `getUTC*` 方法读取时间分量，基于绝对 UTC 毫秒 + 时区偏移计算；主题（白天/黑夜）跟随当前显示时钟所在时区的昼夜变化
- **动画逻辑统一**：新增时区时钟与本地时钟动画完全一致，仍为反转表盘（环旋转、指针固定）
- **长按删除时钟**：长按表盘 500ms 进入删除模式，加号变形为红色垃圾桶图标，点击删除当前时钟；本地时间时钟不可删除
- **时区去重**：已添加时钟的时区无法再添加相同的时钟，城市选项中已添加时区的选项显示为禁用态（半透明且不可点击）
- **时钟标签**：每个时钟上方显示城市与时区（如 `北京 · UTC+8`），切换时钟时标签随表盘同步淡出/恢复
- **跨会话记忆**：添加的时钟与当前索引通过 `localStorage`（key `clock_app_v1`）持久化，重启后恢复

### 体验优化（v1.1）
- **120 帧切换动画**：多时区切换动画支持 120Hz 高刷新率，CSS transition 采用 `cubic-bezier(0.22, 1, 0.36, 1)`（easeOutQuint）丝滑曲线，关键元素 `will-change: transform` 提升合成层，`switching` class 与 `SWITCH_DURATION` 同步移除硬编码
- **加号按钮无闪烁展开**：城市卡片展开消除闪烁，`height` 改为固定值（220px）替代 `auto`（不可插值），`city-card` 淡入与容器同步无延迟，`will-change: transform, width, height` 合成层提升；安卓原生 `WebSettings.setRenderPriority(HIGH)` 高性能渲染
- **深色模式纯黑背景**：夜晚主题 `--bg/--bg-soft/--bg-deep` 统一为 `#000000` 纯黑，表盘 `--surface` 保留深色立体感

### 体验优化（v1.3）
- **全元素 120Hz 适配**：为表盘 `.clock`、点式进度条 `.dot`、加号图标等所有动画元素补齐 `will-change` 合成层提升；`.dot` 过渡改用显式可合成属性（width/background/box-shadow/border-radius），避免 `transition: all` 触发非合成动画；rAF 主循环每帧仅写 transform、无布局读取
- **空闲 60Hz 降帧省电**：用户无任何操作满 60 秒后，主循环按时间节流到 ~60fps（仅丢帧）；任意触摸/点击瞬间恢复全刷新率（120Hz 设备即 120fps）；此切换仅影响帧率，不改变任何动画时长/曲线/视觉效果
- **长按表盘删除时钟**：长按表盘 500ms 进入"删除模式"——表盘丝滑按压动画（非线性 缩小→放大），加号按钮的"+"丝滑变形为红色垃圾桶图标，点击即可删除当前时钟并丝滑切换到相邻时钟；仅剩 1 个时钟时不可删除
- **进度条丝滑切换**：将进度条构建与切换分离，切换时钟时仅切换 `.active` 类（不重建 DOM），CSS 过渡在 120Hz 下丝滑无跳变

### 体验优化（v1.3 续）
- **切换性能优化**：移除 132 个刻度 + 30 个数字 + 环对 `box-shadow`/`background` 的 1.5s 过渡（不可合成，引发重绘风暴），仅保留 `body` + `.clock` 两个视觉主角过渡，昼夜时钟切换不再卡顿
- **全屏铺满**：`MainActivity` 新增 `FLAG_LAYOUT_NO_LIMITS`，WebView 与窗口背景设为日间淡蓝 `#D9E6F4`，消除全面屏手机状态栏黑边
- **加号卡片排版修复**：展开高度 220→264px 容下 4 行，选项 `min-width` 88→72px、padding 收紧，末行不再溢出（含 320px 窄屏 3 列保证）
- **消除蓝色闪烁**：全局 `-webkit-tap-highlight-color: transparent` + `*:focus{outline:none}`，展开/点击无蓝色长方形
- **时钟标签**：每个时钟上方居中显示 `城市 · UTC±h(:mm)`，随切换同步淡出/恢复
- **跨会话记忆**：已添加的时钟与当前索引通过 `localStorage`（key `clock_app_v1`）持久化，重启后恢复

### 体验优化（v1.4）
- **长按删除按钮恒可达**：时钟达上限（6 个）时加号按钮虽已隐藏，但长按表盘 500ms 仍强制显示红色垃圾桶删除按钮；退出删除模式后按当前时钟数恢复隐藏状态，确保任意时刻均可长按删除
- **城市卡片四周等宽留白**：展开态 `padding` 统一为 16px + `align-content: space-between` 行均匀分布，使最上/最下两行与左右两侧距边框距离相等
- **进度点恒圆**：进度点统一 `border-radius: 999px` 且不对 `border-radius` 过渡，切换 active 时不再出现"先变方形再变圆"的中间态（非 active 为正圆、active 为胶囊）
- **APK 原地覆盖更新**：`build.ps1` 改为持久化复用同一签名密钥（稳定路径 `android/release.keystore`，仅在缺失时生成），新 APK 可在未卸载旧版本的情况下直接覆盖安装
- **时钟标签核验**：确认每个时钟上方均显示 `城市 · UTC±h(:mm)`，启动/切换/添加/删除各流程均正确更新

### 体验打磨（v1.6 续）
- **刷新率三级切换**：将原“空闲 60 秒后 60Hz”扩展为三级——活跃态全速（120Hz 设备即 120fps）；10 秒无操作降为 60Hz；1 分钟无操作降为 30Hz；任意触摸/点击立即恢复全速，仅丢帧不改变动画时长/曲线/视觉效果
- **Android 13 全屏修复**：MainActivity 显式将状态栏与导航栏背景设为透明（`setStatusBarColor`/`setNavigationBarColor` TRANSPARENT），并在 `onWindowFocusChanged` 中显式隐藏 `navigationBars()`，消除 Android 13（API 33）等设备导航栏黑色残留，兼容所有全面屏
- **震动更短促清脆**：将 `vibrateAdd` 与 `vibrateDelete` 的单次 on 时长由 30ms 降为 10ms，间隔同步收紧（`vibrateAdd` 波形 `{0, 10, 40, 10}`、`vibrateDelete` 波形 `{0, 10, 35, 10, 35, 10, 35, 10}`），触感更短促清脆

### 体验优化（v1.6）
- **应用图标专属化**：移除通用 `ic_launcher`，使用项目专属 `clock_icon.png`（1024×1024）缩放为 5 个 Android 密度（mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144 / xxxhdpi 192），各密度清晰无失真，透明背景保留
- **加号按钮不可文字选中**：为 `.add-button` 添加 `user-select: none`（含 `-webkit-user-select`），"+"字符不可被长按当作文字选区高亮，成为纯点击按钮
- **触觉反馈（震动）**：新增 JS↔Android 震动桥（`VibrationInterface`），点击加号按钮与长按时钟进入删除模式时触发 2 次短促清脆震动，点击删除按钮时触发 4 次短促连续震动；新增 `android.permission.VIBRATE` 权限；MainActivity 向 WebView 注入 `window.AndroidVibration` 桥对象；API 26+ 用 `VibrationEffect.createWaveform`，API 21-25 用已废弃 `vibrate(long[], int)`；无马达设备静默降级

### 体验优化（v1.7）
- **震动波形升级（参考 iOS 长按桌面图标）**：`vibrateAdd` 改为 3 次脉冲、间隔递增（45ms→75ms）、振幅渐强（70→120→170/255）；`vibrateDelete` 改为 4 次脉冲、间隔递增（35ms→55ms→80ms）、振幅渐强（80→120→160→200/255）；触感由短促机械感升级为 iOS 式层次感
- **时区选项震动反馈**：点击加号卡片中非禁用时区选项时触发与加号按钮相同的 `vibrateAdd` 震动，交互一致
- **全屏主题层适配**：新增 `Theme.Clock` 自定义主题（继承 `Theme.Material.NoActionBar`），在主题层声明 `windowBackground`（日间淡蓝）、`windowLayoutInDisplayCutoutMode=shortEdges`（刘海屏全面铺满）、透明系统栏色与 `windowDrawsSystemBarBackgrounds`；从主题层根治 Android 13 等设备导航栏黑边，兼容所有全面屏

### 体验优化（v1.78）
- **主题随时间线性渐变**：从昼夜二值硬切换改为连续线性渐变——正午 12:00 淡蓝峰值（t=1）↔ 午夜 00:00 纯黑谷值（t=0），00:00→12:00 线性升、12:00→24:00 线性降，全部主题变量（背景/表面/文字/阴影/光源偏移）每秒刷新
- **小组件配色与 app 同步**：桌面小组件配色随时间渐变与 app 完全一致（淡蓝/纯黑线性插值），不再使用独立配色
- **小组件秒级更新**：更新间隔缩短为 1 秒，秒针走动可见；时针盘 3/6/9/12、分针盘 0/10/20/30/40/50、秒针盘 0/30；防烧屏 8 步偏移
- **APK 重命名**：构建产物统一为 clock.apk

### 体验优化（v1.5）
- **本地时钟不可删除**：删除规则从"仅剩 1 个时钟不可删除"改为"本地时间时钟不可删除"——查看本地时钟时长按表盘不进入删除模式，查看非本地时钟时可正常删除，删除最后一个非本地时钟后自动回到本地时钟且后续长按无响应
- **城市卡片四周等宽留白**：展开态 `align-content` 从 `flex-start` 改为 `space-between`，首行贴顶、末行贴底，四周留白等宽（18px），消除底部留白过大问题
- **时区去重**：已添加时钟的时区无法再添加相同的时钟——`addClock()` 增加时区重复检查，城市选项中已添加时区的选项显示为禁用态（半透明且不可点击），删除时钟后对应时区恢复可选；本地时区匹配的城市选项在启动时即为禁用态

### 视觉系统
- **随时间线性渐变主题**：正午 12:00 为白天淡蓝峰值（t=1），午夜 00:00 为夜晚纯黑谷值（t=0），00:00→12:00 线性升、12:00→24:00 线性降，全部主题变量（背景/表面/文字/阴影/光源偏移）连续插值，每秒刷新
- **新拟态 (Neumorphism)**：柔和的凸起/凹陷阴影，质感细腻

### Android 专属
- **全屏沉浸模式**：无状态栏、导航栏，纯时钟界面
- **桌面小组件**：2×2 圆角矩形小组件，每 1 秒更新，配色与 app 内时间渐变完全一致（淡蓝/纯黑线性插值），时针盘仅 3/6/9/12，分针盘 0/10/20/30/40/50，秒针盘 0/30，防烧屏 8 步偏移
  - 纯 Canvas 原生绘制（不依赖 WebView），新拟态风格
- **屏幕常亮**：作为时钟应用保持屏幕不熄灭
- **全屏不可移动、不可缩放**：移动端全面优化，禁用滚动条与缩放，时钟居中固定

### 功耗优化
- **WebView 时钟**：
  - DOM 查询缓存，避免每帧重复查询
  - Page Visibility API：页面不可见时暂停动画循环
  - **空闲三级降帧**：用户无操作 10 秒后动画降至 60Hz，1 分钟后进一步降至 30Hz，触碰即恢复 120Hz，兼顾流畅与功耗
- **小组件渲染**：
  - 1 秒更新间隔（秒针走动可见）
  - 复用 Bitmap（`eraseColor` 擦除重绘）和 Paint 对象，消除 GC 压力
- **主界面**：
  - 失去窗口焦点时暂停 WebView JS 定时器
  - 夜间自动降低屏幕亮度至 30%

### 防烧屏机制
- **WebView 时钟**：`clock-wrapper` 容器每 5 分钟沿垂直方向循环微偏移（8 步，最大 4px，6 秒非线性 `cubic-bezier(0.65, 0, 0.35, 1)` 缓动）
- **小组件**：Canvas 绘制坐标系每秒沿垂直方向循环偏移（8 步，最大 2px）
- 偏移量在视觉不可察觉范围内，有效防止 OLED 静态像素烙印

---

## 📁 项目结构

```
clock/
├── clock.apk               # 预编译的 Android APK（可直接安装，版本 1.78）
├── README.md               # 本文件
└── android/                # Android 应用源码
    ├── AndroidManifest.xml # 应用清单（权限、组件注册，versionCode=11, versionName=1.78）
    ├── build.ps1           # PowerShell 构建脚本（手动构建 APK，持久化复用签名密钥，指向 1.8 项目目录）
    ├── assets/
    │   └── index.html      # 时钟 HTML（网页版主时钟，单文件包含所有 HTML/CSS/JS，含多时区时钟、城市卡片、点式进度条、滑动切换）
    ├── src/com/clock/app/
    │   ├── MainActivity.java        # 主界面（全屏 WebView 时钟 + 功耗管理）
    │   ├── ClockWidgetProvider.java # 小组件管理器（定时更新）
    │   └── ClockRenderService.java  # 小组件渲染服务（Canvas 绘制 + 防烧屏）
    └── res/
        ├── layout/widget_clock.xml  # 小组件布局（RemoteViews）
        ├── xml/widget_info.xml      # 小组件元数据（尺寸、更新间隔）
        ├── drawable/widget_bg.xml   # 小组件背景（圆角矩形）
        ├── values/strings.xml       # 字符串资源
        ├── values/styles.xml        # 自定义全屏主题（Theme.Clock）
        ├── values/colors.xml        # 窗口背景色（window_bg）
        └── mipmap-*/ic_launcher.png # 应用图标（5 个密度版本）
```

---

## 🚀 快速开始

### 网页版

**方式一：直接打开**
直接双击 `index.html` 即可在浏览器中运行。

> ⚠️ 直接打开本地文件时，网络时间同步可能因浏览器安全策略无法生效，时钟将使用本地系统时间。

**方式二：本地服务器（推荐）**
```bash
python -m http.server 8080
# 访问 http://localhost:8080
```

**时间模拟（调试用）**
```
index.html?hour=14&minute=30    # 模拟下午 2:30（白天主题）
index.html?hour=18&minute=0     # 模拟傍晚 18:00（切换为黑夜主题）
index.html?hour=0&minute=0      # 模拟午夜 0:00（黑夜主题）
```

### Android 应用

**方式一：安装预编译 APK**
将 `clock.apk` 传输到 Android 设备，直接安装即可。

> 首次安装可能需要在设置中允许"未知来源"安装。

**方式二：从源码构建**
```powershell
# 需要 JDK 25+ 和 Android SDK（build-tools 37.0.0, platform android-36.1）
cd c:\Users\Xzf13\Desktop\clock\1.8\android
powershell -ExecutionPolicy Bypass -File build.ps1
# 构建完成后 APK 输出到 c:\Users\Xzf13\Desktop\clock\1.8\clock.apk
# 首次构建会在 android\release.keystore 生成签名密钥；后续构建复用同一密钥，支持覆盖更新
```

---

## 🏗️ 架构设计

### 反转表盘原理

传统时钟：**指针旋转，刻度固定**
本时钟：**刻度环反向（逆时针）旋转，指针固定指向 12 点**

当某个刻度转到固定指针位置时，该刻度即为当前时间值。

### 角度计算

| 环 | 角度公式 | 转速 |
|---|---|---|
| 秒环 | `-(秒 + 毫秒/1000) × 6°` | 6°/秒，1 分钟转一圈 |
| 分钟环 | `-(分 + 秒/60 + 毫秒/60000) × 6°` | 6°/分，1 小时转一圈 |
| 小时环 | `-((时%12) + 分/60 + 秒/3600) × 30°` | 30°/小时，12 小时转一圈 |

负号表示逆时针旋转。

### 数字正位算法

环上的数字随环旋转，但需要始终保持正向（不倒立）：
```
1. rotate(ringAngle)      — 数字随环公转到对应位置
2. translateY(-radius)    — 移动到环的边缘
3. rotate(-ringAngle)     — 数字反向自旋，保持 upright
```

### 多时区时钟架构

- **时钟列表数据结构**：`clocks` 数组，每项包含 `city`（城市名）与 `tzOffsetMinutes`（相对 UTC 的分钟偏移）；首项为本地默认时钟
- **时区时间计算**：`getTimezoneDate` 基于绝对 UTC 毫秒 + 时区偏移（`tzOffsetMinutes * 60000`）合成目标时区时间，再以 `getUTC*` 方法读取时间分量，避免本地时区污染
- **切换非线性动画**：`switchClock` 配合 `easeInOutCubic` 缓动函数对索引进行插值，叠加 `switching` class 实现背景/时钟的透明度与缩放丝滑过渡（800ms）
- **按钮无极变形**：单 DOM 元素通过 CSS `transition` 平滑过渡 `width/height/border-radius/box-shadow`，由"+"按钮形态无极变身为城市选择卡片
- **滑动检测**：监听 `touchstart`/`touchend`，比较起止横坐标差值，阈值 50px 触发相邻时钟切换
- **时区去重**：`addClock()` 在添加前遍历 `clocks` 数组比对 `tzOffsetMinutes`，重复时直接返回；`buildCityOptions()` 为已添加时区的城市选项添加 `disabled` 类（半透明、不可点击）

### Android 小组件架构

```
ClockWidgetProvider (AppWidgetProvider)
  ├── onEnabled()     → 启动 AlarmManager 定时任务（每 1 秒）
  ├── onUpdate()      → 启动 ClockRenderService
  ├── onReceive()     → 处理定时广播，启动 ClockRenderService
  └── onDisabled()    → 取消 AlarmManager 定时任务

ClockRenderService (Service, START_STICKY)
  ├── Handler 每 1 秒触发重绘
  ├── Canvas 绘制时钟位图（500×500px，复用 Bitmap）
  │   ├── 裁剪圆角矩形
  │   ├── app 风格背景（径向渐变）+ 圆形表盘（新拟态凸起阴影）
  │   ├── 时/分/秒三环（旋转）
  │   ├── 固定指针（主体 + 高光 + 阴影）
  │   ├── 中心点（径向渐变 + 高光）
  │   ├── 配色随时间渐变与 app 同步
  │   └── 防烧屏坐标系偏移（垂直方向 8 步循环，最大 2px）
  └── RemoteViews.setImageViewBitmap() → 更新小组件
```

---

## 🔧 技术栈

### 网页版
- **HTML5** — 语义化结构
- **CSS3** — 自定义属性、渐变、clip-path、动画、新拟态阴影
- **原生 JavaScript (ES5)** — 零依赖，兼容所有现代浏览器
- **XMLHttpRequest** — 网络时间同步
- **requestAnimationFrame** — 流畅的 60fps 动画
- **Page Visibility API** — 后台暂停动画节省功耗
- **Touch Events API** — 多时钟滑动切换

### Android 版
- **Java** — 原生 Android Activity + Service
- **WebView** — 渲染 HTML 时钟界面（禁用缩放、滚动条、过度滚动）
- **Canvas API** — 绘制桌面小组件位图
- **Vibrator / VibrationEffect** — 触觉反馈（震动），API 26+ 使用 VibrationEffect；波形采用间隔递增 + 振幅渐强（多脉冲层次感）
- **JavascriptInterface** — JS↔Java 桥，注入 window.AndroidVibration 供 WebView 调用震动
- **AppWidgetProvider** — 管理小组件生命周期
- **AlarmManager** — 定时触发小组件更新
- **Android SDK build-tools 37.0.0** — 手动构建（无 Gradle 依赖）
- **targetSdkVersion 36** — 兼容 Android 16

---

## 🌐 兼容性

### 网页版
- Chrome/Edge 60+、Firefox 55+、Safari 12+、现代移动端浏览器
- 必需特性：CSS 自定义属性、CSS clip-path、requestAnimationFrame、URLSearchParams、Touch Events

### Android 版
- 最低支持：Android 5.0（API 21）
- 目标版本：Android 16（API 36）
- 已测试适配：小米/HyperOS、三星/OneUI

---

## 📄 License

MIT
