/*
 * ========================================================================
 * 反转表盘时钟 - Android 应用主界面
 * ========================================================================
 *
 * 功能说明：
 *   本 Activity 是应用的唯一界面，启动后全屏显示反转表盘时钟。
 *   通过 WebView 加载 assets/index.html 实现完整的时钟 UI，包括：
 *   - 反转表盘机制（指针固定于 12 点，时/分/秒刻度环逆时针旋转）
 *   - 动态光影系统（五层光束模拟日照轨迹）
 *   - 时间渐变主题（正午白天峰值/午夜夜晚谷值，全部主题变量线性插值）
 *   - 网络时间同步（通过 HTTP Date 头校准）
 *   - 新拟态（Neumorphism）视觉风格
 *
 * 功耗优化：
 *   - 失去焦点时暂停 WebView（暂停 JS 定时器和动画循环）
 *   - 夜间自动降低屏幕亮度（OLED 屏幕深色像素更省电）
 *   - onResume/onPause 正确管理 WebView 生命周期
 *
 * 全屏策略：
 *   - Android 11+（API 30）：使用 WindowInsetsController 隐藏系统栏
 *   - Android 5.0-10（API 21-29）：使用 FLAG_FULLSCREEN + SYSTEM_UI_FLAG 组合
 *   - 窗口重新获焦时自动恢复全屏状态
 *
 * 兼容性：
 *   最低支持 Android 5.0（API 21），目标版本 Android 16（API 36）
 *   已适配小米/HyperOS、三星/OneUI 等定制系统
 * ========================================================================
 */
package com.clock.app;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Calendar;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.webkit.JavascriptInterface;

/**
 * 应用主 Activity
 *
 * 使用 WebView 全屏渲染 HTML 时钟界面。
 * WebView 配置：JavaScript 启用、DOM 存储启用、硬件加速、文件访问允许。
 * 所有页面导航均在 WebView 内部处理，不会跳出应用。
 *
 * 功耗优化策略：
 *   - 失去窗口焦点时暂停 WebView JS（如被通知栏遮挡）
 *   - 夜间（20:00-05:00）自动降低屏幕亮度至 30%
 *   - 亮度每 5 分钟检查一次，避免频繁调整
 */
public class MainActivity extends Activity {

    /** 时钟 WebView 实例 */
    private WebView webView;

    /** 亮度调节 Handler */
    private Handler brightnessHandler;

    /** 当前是否处于低亮度模式 */
    private boolean isDimmed = false;

    /** 亮度检查间隔：5 分钟 */
    private static final long BRIGHTNESS_CHECK_INTERVAL = 5 * 60 * 1000;

    /** 夜间低亮度值（0.0-1.0），30% 亮度在 OLED 上显著省电 */
    private static final float NIGHT_BRIGHTNESS = 0.3f;

    /** 系统震动器实例（用于触觉反馈） */
    private Vibrator vibrator;

    /** 当前设备是否具备马达（无马达时震动调用静默降级） */
    private boolean hasVibrator = false;

    /**
     * Activity 创建回调
     * 初始化全屏模式 → 创建 WebView → 加载时钟页面 → 启动亮度调节
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 保持屏幕常亮（时钟应用需要持续显示）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 根据系统版本设置全屏沉浸模式
        setupFullscreen();

        // 窗口背景设为日间淡蓝，避免状态栏区域露出默认深色
        getWindow().setBackgroundDrawable(new ColorDrawable(0xFFD9E6F4));

        // 初始化震动器（API 31+ 走 VibratorManager，低版本走 VIBRATOR_SERVICE）
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = (vm != null) ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        hasVibrator = (vibrator != null && vibrator.hasVibrator());

        // 创建并配置 WebView
        webView = new WebView(this);
        configureWebView(webView);

        // 从本地 assets 加载时钟 HTML 页面
        webView.loadUrl("file:///android_asset/index.html");

        setContentView(webView);

        // 初始化亮度调节
        brightnessHandler = new Handler(Looper.getMainLooper());
        brightnessHandler.post(brightnessRunnable);
    }

    /**
     * 设置全屏沉浸模式（根据 Android 版本分别处理）
     * 主题层（Theme.Clock）已声明 windowBackground 与 cutout shortEdges，此处保留代码层兜底
     */
    private void setupFullscreen() {
        // 透明状态栏与导航栏背景，消除 Android 13 等设备导航栏黑边
        // （系统栏本身由下方 InsetsController/setSystemUiVisibility 隐藏，
        //   设为透明可兜底某些 3 键导航/定制系统残留底色的场景）
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        // 窗口延伸至状态栏/导航栏 inset 之下，铺满整屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+：WindowInsetsController 现代方案
            getWindow().setDecorFitsSystemWindows(false);
            View decorView = getWindow().getDecorView();
            decorView.post(new Runnable() {
                @Override
                public void run() {
                    WindowInsetsController controller = getWindow().getInsetsController();
                    if (controller != null) {
                        controller.hide(WindowInsets.Type.systemBars());
                        controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        );
                    }
                }
            });
        } else {
            // Android 5.0-10：传统标志组合
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY       // 粘性沉浸
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE         // 布局稳定
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION // 布局延伸到导航栏
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN     // 布局延伸到状态栏
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION       // 隐藏导航栏
                | View.SYSTEM_UI_FLAG_FULLSCREEN            // 隐藏状态栏
            );
        }
    }

    /**
     * 配置 WebView 参数
     *
     * @param wv 需要配置的 WebView 实例
     */
    private void configureWebView(WebView wv) {
        WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);       // 启用 JavaScript（时钟动画必需）
        settings.setDomStorageEnabled(true);       // 启用 DOM 存储
        settings.setUseWideViewPort(true);         // 使用宽视口（响应式适配）
        settings.setLoadWithOverviewMode(true);    // 概览模式加载
        settings.setAllowFileAccess(true);         // 允许访问本地文件
        settings.setSupportZoom(false);            // 禁用缩放（全屏时钟不需要）
        settings.setBuiltInZoomControls(false);    // 禁用内置缩放控件
        settings.setDisplayZoomControls(false);    // 不显示缩放控件
        // 高性能渲染：优先使用安卓原生技术提升 WebView 渲染优先级（120帧动画流畅）
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH);
        // 禁用 WebView 滚动条与过度滚动，确保画布不可移动
        wv.setVerticalScrollBarEnabled(false);
        wv.setHorizontalScrollBarEnabled(false);
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // 启用硬件加速，确保 CSS 动画和 transform 流畅运行
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // WebView 背景设为日间淡蓝，避免首帧白屏与状态栏区域黑边（夜间 body 纯黑会覆盖此色）
        wv.setBackgroundColor(0xFFD9E6F4);

        // 注入震动桥对象，供 JS 通过 window.AndroidVibration 调用触觉反馈
        wv.addJavascriptInterface(new VibrationInterface(), "AndroidVibration");

        // 所有页面导航在 WebView 内部处理（不跳转到外部浏览器）
        wv.setWebViewClient(new WebViewClient());
    }

    /* ====================================================================
     *  屏幕亮度调节 — 夜间降低亮度节省 OLED 功耗
     * ==================================================================== */

    /**
     * 判断当前是否为夜间时段（20:00-05:00）
     * @return true 表示夜间
     */
    private boolean isNighttime() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 20 || hour < 5;
    }

    /**
     * 更新屏幕亮度
     * 夜间降低到 NIGHT_BRIGHTNESS（30%），白天恢复默认（系统亮度）
     */
    private void updateBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (isNighttime()) {
            if (!isDimmed) {
                lp.screenBrightness = NIGHT_BRIGHTNESS;
                getWindow().setAttributes(lp);
                isDimmed = true;
            }
        } else {
            if (isDimmed) {
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
                isDimmed = false;
            }
        }
    }

    /**
     * 定时亮度检查任务：每 5 分钟检查一次是否需要调整亮度
     */
    private final Runnable brightnessRunnable = new Runnable() {
        @Override
        public void run() {
            updateBrightness();
            brightnessHandler.postDelayed(this, BRIGHTNESS_CHECK_INTERVAL);
        }
    };

    /* ====================================================================
     *  全屏恢复
     * ==================================================================== */

    /**
     * 窗口焦点变化回调
     * 当应用重新获得焦点时恢复全屏状态（例如从通知栏返回）
     * 失去焦点时暂停 WebView JS，重新获焦时恢复
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 恢复全屏模式
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = getWindow().getInsetsController();
                    if (controller != null) {
                        controller.hide(WindowInsets.Type.systemBars());
                        controller.hide(WindowInsets.Type.navigationBars());
                    }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                );
            }
            // 恢复 WebView JS 执行（暂停期间 JS 定时器已冻结）
            if (webView != null) {
                webView.onResume();
            }
        } else {
            // 失去焦点：暂停 WebView JS 定时器，节省 CPU/GPU 功耗
            if (webView != null) {
                webView.onPause();
            }
        }
    }

    /** Activity 恢复时恢复 WebView 状态 */
    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
        // 立即检查亮度（从后台返回时可能需要调整）
        updateBrightness();
    }

    /** Activity 暂停时暂停 WebView（节省资源） */
    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    /* ====================================================================
     *  触觉反馈 — JS↔Android 震动桥
     *  注入 WebView 为 window.AndroidVibration，供 index.html 调用。
     *  所有方法由 WebView 的 JS 线程触发，仅做一次性震动，无状态依赖。
     *  无马达设备（部分模拟器/平板）静默降级，不崩溃。
     * ==================================================================== */

    /**
     * 震动桥内部类
     * 通过 @JavascriptInterface 暴露给 WebView 中的 JavaScript。
     * - vibrateAdd():    点击加号按钮 / 长按时钟进入删除模式 → 3 次短促震动
     * - vibrateDelete(): 点击删除按钮 → 4 次短促连续震动
     */
    private class VibrationInterface {

        /**
         * 3 次短促震动，相邻间隔递增（45ms→75ms），振幅渐强（70→120→170/255）
         * 触发场景：点击加号按钮（展开/收起城市卡片）、长按时钟进入删除模式、点击时区选项
         */
        @JavascriptInterface
        public void vibrateAdd() {
            if (!hasVibrator) return;
            // 波形：等待0ms → 震动12ms → 间隔45ms → 震动14ms → 间隔75ms → 震动16ms（3 次脉冲，间隔 45→75 递增）
            long[] pattern = {0, 12, 45, 14, 75, 16};
            // 振幅：等待段为 0，震动段 70→120→170 渐强（参考 iOS 长按桌面图标效果）
            int[] amplitudes = {0, 70, 0, 120, 0, 170};
            if (Build.VERSION.SDK_INT >= 26) {
                // API 26+ 使用 VibrationEffect 指定振幅（推荐）
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1));
            } else {
                // API 21-25 使用已废弃的 vibrate(long[], int)，不支持振幅，on 时长已缩短以减轻力度
                vibrator.vibrate(pattern, -1);
            }
        }

        /**
         * 4 次短促震动，相邻间隔递增（35ms→55ms→80ms），振幅渐强（80→120→160→200/255）
         * 触发场景：点击删除按钮（删除当前时钟）
         */
        @JavascriptInterface
        public void vibrateDelete() {
            if (!hasVibrator) return;
            // 波形：等待0ms → 震动12ms → 间隔35ms → 震动14ms → 间隔55ms → 震动16ms → 间隔80ms → 震动18ms（4 次脉冲，间隔 35→55→80 递增）
            long[] pattern = {0, 12, 35, 14, 55, 16, 80, 18};
            // 振幅：等待段为 0，震动段 80→120→160→200 渐强
            int[] amplitudes = {0, 80, 0, 120, 0, 160, 0, 200};
            if (Build.VERSION.SDK_INT >= 26) {
                // API 26+ 使用 VibrationEffect 指定振幅（推荐）
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1));
            } else {
                // API 21-25 使用已废弃的 vibrate(long[], int)，不支持振幅，on 时长已缩短以减轻力度
                vibrator.vibrate(pattern, -1);
            }
        }
    }

    /**
     * Activity 销毁时彻底清理 WebView 和亮度调节
     *
     * 清理顺序（防止内存泄漏）：
     *   1. 停止亮度调节定时器
     *   2. stopLoading()   - 停止所有加载
     *   3. clearHistory()  - 清除浏览历史
     *   4. loadUrl(blank)  - 加载空白页释放页面资源
     *   5. removeAllViews()- 移除所有子视图
     *   6. destroy()       - 销毁 WebView 实例
     */
    @Override
    protected void onDestroy() {
        if (brightnessHandler != null) {
            brightnessHandler.removeCallbacks(brightnessRunnable);
        }
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
