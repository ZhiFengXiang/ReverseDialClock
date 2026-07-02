/*
 * ========================================================================
 * 反转表盘时钟 - 桌面小组件渲染服务（Service）
 * ========================================================================
 *
 * 功能说明：
 *   在后台每 1 秒重绘反转表盘时钟位图，并推送到所有已添加的桌面小组件。
 *   绘制结果与 app 内 WebView 中的 HTML 时钟视觉一致：
 *     - 匹配 app 内时钟视觉（淡蓝 INS 拟态 day 主题 + 纯黑 night 主题）
 *     - 时间渐变配色：随当前时刻在 DAY / NIGHT 两套调色板之间线性插值
 *       （正午 12:00 为白天峰值 t=1，午夜 00:00 为夜晚谷值 t=0，
 *         06:00 与 18:00 为半亮 t=0.5）
 *     - 1 秒刷新（秒针走动可见，与 app 内时钟同步）
 *     - 精简数字：时针盘仅 3/6/9/12、分针盘 0/10/20/30/40/50、秒针盘 0/30
 *     - 圆角矩形裁剪：Canvas 以圆角矩形路径裁剪，背景圆角与小组件外形一致
 *     - 防烧屏 8 步偏移：每秒在垂直方向以 0→1→2→1→0→-1→-2→-1 循环微移，
 *       避免 OLED 屏幕长时间显示静态画面导致残影
 *
 * 反转表盘机制：
 *   指针固定于 12 点方向，时/分/秒刻度环随时间逆时针旋转
 *   （hourAngle / minAngle / secAngle 均取负值，实现“盘转针不转”）
 *
 * 性能优化：
 *   - 复用单个 500×500 ARGB_8888 Bitmap 与 Canvas，每帧 eraseColor(0) 重绘
 *   - 复用 5 个 Paint 对象（paintA-E）与 Path / RectF，避免每秒 GC
 *   - 复用单个 Palette 对象（currentPalette）承载当前插值结果
 *   - 渲染在主线程完成（500×500 软件画布，单帧约 2-5ms，可接受）
 * ========================================================================
 */
package com.clock.app;

import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.RemoteViews;

import java.util.Calendar;

/**
 * 时钟渲染服务
 *
 * 负责将反转表盘时钟绘制到一张 500×500 的位图上，并通过 RemoteViews
 * 更新到所有桌面小组件。每 1 秒触发一次重绘。
 */
public class ClockRenderService extends Service {

    /* ====================================================================
     *  常量定义
     * ==================================================================== */

    /** 位图边长（正方形，500×500）。ImageView 以 fitCenter 显示，铺满 2×2 方形小组件 */
    private static final int SIZE = 500;

    /** 自动刷新间隔：1 秒（秒针走动可见） */
    private static final long UPDATE_INTERVAL = 1_000;

    /** 圆角矩形的圆角半径（SIZE*0.09 ≈ 45px，与 app 内 widget 圆角一致） */
    private static final float CORNER_RADIUS = 45f;

    /**
     * 防烧屏 8 步垂直偏移序列
     * 每秒切换一步：0→1→2→1→0→-1→-2→-1，使画面在垂直方向做 ±2px 微移，
     * 避免 OLED 像素长时间点亮同一位置产生残影
     */
    private static final int[][] BURN_IN_OFFSETS = {
            {0, 0}, {0, 1}, {0, 2}, {0, 1},
            {0, 0}, {0, -1}, {0, -2}, {0, -1}
    };

    /* ---------------- DAY 调色板（t=1，正午 12:00） ---------------- */
    // 背景层（淡蓝 INS 拟态）
    private static final int D_BG = 0xFFD9E6F4;
    private static final int D_BG_SOFT = 0xFFE9F2FA;
    private static final int D_BG_DEEP = 0xFFC5D6EA;
    // 表面层（新拟态凸起）
    private static final int D_SURFACE = 0xFFE6EEF7;
    private static final int D_SURFACE_SOFT = 0xFFF3F8FC;
    private static final int D_SURFACE_DEEP = 0xFFD2E0EE;
    // 蓝色系（由浅到深，刻度/指针用）
    private static final int D_BLUE1 = 0xFFC9DCE8;
    private static final int D_BLUE2 = 0xFFA8C5DA;
    private static final int D_BLUE3 = 0xFF7FA8C4;
    private static final int D_BLUE4 = 0xFF5A8CB0;
    // 墨色系（主文字/主刻度）
    private static final int D_INK = 0xFF2C4A64;
    private static final int D_INK_SOFT = 0xFF4A6880;
    // 阴影色（高光 / 暗影，含 alpha）
    private static final int D_SH_LIGHT = 0xE6FFFFFF;      // rgba(255,255,255,0.9)
    private static final int D_SH_LIGHT_SOFT = 0x80FFFFFF; // rgba(255,255,255,0.5)
    private static final int D_SH_DARK = 0x2928466E;       // rgba(40,70,110,0.16)
    private static final int D_SH_DARK_SOFT = 0x1228466E;  // rgba(40,70,110,0.07)
    // 光源偏移（决定阴影方向，px）
    private static final float D_LIGHT_DX = 10f;
    private static final float D_LIGHT_DY = 16f;

    /* ---------------- NIGHT 调色板（t=0，午夜 00:00） ---------------- */
    // 背景层（纯黑）
    private static final int N_BG = 0xFF000000;
    private static final int N_BG_SOFT = 0xFF000000;
    private static final int N_BG_DEEP = 0xFF000000;
    // 表面层（深灰蓝）
    private static final int N_SURFACE = 0xFF1A2028;
    private static final int N_SURFACE_SOFT = 0xFF232A35;
    private static final int N_SURFACE_DEEP = 0xFF12171D;
    // 蓝色系（夜间提亮，保证暗背景上可读）
    private static final int N_BLUE1 = 0xFF4A6080;
    private static final int N_BLUE2 = 0xFF5D7598;
    private static final int N_BLUE3 = 0xFF7A95B8;
    private static final int N_BLUE4 = 0xFF9BB5D4;
    // 墨色系（夜间改用浅色文字）
    private static final int N_INK = 0xFFC8D8E8;
    private static final int N_INK_SOFT = 0xFF8A9CB5;
    // 阴影色（夜间降低对比，避免亮边刺眼）
    private static final int N_SH_LIGHT = 0x14788CAA;      // rgba(120,140,170,0.08)
    private static final int N_SH_LIGHT_SOFT = 0x0A788CAA; // rgba(120,140,170,0.04)
    private static final int N_SH_DARK = 0x80000000;       // rgba(0,0,0,0.5)
    private static final int N_SH_DARK_SOFT = 0x4D000000;  // rgba(0,0,0,0.3)
    // 光源偏移（夜间缩短，阴影更收敛）
    private static final float N_LIGHT_DX = 5f;
    private static final float N_LIGHT_DY = 5f;

    /* ====================================================================
     *  可复用绘制对象（避免每秒 GC）
     * ==================================================================== */

    /** 可复用的 500×500 ARGB 位图 */
    private Bitmap reusableBmp;
    /** 绑定到 reusableBmp 的画布 */
    private Canvas reusableCanvas;
    /** 可复用路径（圆角矩形裁剪用） */
    private final Path reusablePath = new Path();
    /** 可复用矩形（绘制圆角矩形/指针用） */
    private final RectF reusableRect = new RectF();
    /** 模糊滤镜（新拟态阴影用），软件画布下生效 */
    private BlurMaskFilter blurFilter;

    /** 画笔 A：填充（背景、表盘、中心点） */
    private Paint paintA;
    /** 画笔 B：阴影（凸起阴影、内凹环） */
    private Paint paintB;
    /** 画笔 C：刻度线 */
    private Paint paintC;
    /** 画笔 D：数字文本 */
    private Paint paintD;
    /** 画笔 E：指针 */
    private Paint paintE;

    /** 当前时刻插值后的调色板（复用同一对象，每秒覆写字段） */
    private Palette currentPalette;

    /** 主线程 Handler，调度每秒重绘 */
    private Handler handler;
    /** tickRunnable 是否已投递（防止重复 startService 导致多次投递） */
    private boolean tickPosted = false;
    /** 防烧屏步进计数（0-7 循环） */
    private int burnStep = 0;

    /**
     * 调色板持有者：承载当前时刻在 DAY/NIGHT 之间线性插值后的全部颜色与光源偏移。
     * 复用单个实例 currentPalette，每秒由 resolvePalette(float) 覆写。
     */
    private static final class Palette {
        int bg, bgSoft, bgDeep;
        int surface, surfaceSoft, surfaceDeep;
        int blue1, blue2, blue3, blue4;
        int ink, inkSoft;
        int shLight, shLightSoft, shDark, shDarkSoft;
        float lightDx, lightDy;
    }

    /* ====================================================================
     *  生命周期
     * ==================================================================== */

    /**
     * 服务创建：初始化所有可复用对象
     * Bitmap / Canvas / Paint / Palette 均在此一次性分配，后续每帧仅重绘
     */
    @Override
    public void onCreate() {
        super.onCreate();
        reusableBmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        reusableCanvas = new Canvas(reusableBmp);
        blurFilter = new BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL);
        currentPalette = new Palette();
        paintA = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintB = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintC = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintD = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintE = new Paint(Paint.ANTI_ALIAS_FLAG);
        handler = new Handler(Looper.getMainLooper());
    }

    /**
     * 服务启动：投递首次重绘任务
     * 使用 tickPosted 守卫，确保多次 startService 只投递一条 tick 链
     *
     * @return START_STICKY：服务被系统杀死后自动重启，保证小组件持续刷新
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!tickPosted) {
            tickPosted = true;
            handler.post(tickRunnable);
        }
        return START_STICKY;
    }

    /**
     * 每秒重绘任务
     * 1. 查询所有已添加的小组件 id；若为空则停止服务（无小组件可刷新）
     * 2. 绘制时钟到 reusableBmp
     * 3. 构造 RemoteViews 推送到每个小组件
     * 4. 排下一秒的重绘
     */
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ClockRenderService.this);
            int[] ids = mgr.getAppWidgetIds(
                    new ComponentName(ClockRenderService.this, ClockWidgetProvider.class));
            if (ids == null || ids.length == 0) {
                // 没有任何小组件：停止服务，释放资源
                tickPosted = false;
                stopSelf();
                return;
            }
            // 绘制当前时刻时钟
            drawClock();
            // 推送位图到所有小组件（共用同一张位图即可）
            RemoteViews rv = new RemoteViews(getPackageName(), R.layout.widget_clock);
            rv.setImageViewBitmap(R.id.widget_clock_image, reusableBmp);
            for (int id : ids) {
                mgr.updateAppWidget(id, rv);
            }
            // 排下一帧（1 秒后）
            handler.postDelayed(this, UPDATE_INTERVAL);
        }
    };

    /**
     * 服务销毁：移除回调并回收位图，防止内存泄漏
     */
    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(tickRunnable);
        }
        tickPosted = false;
        if (reusableBmp != null) {
            reusableBmp.recycle();
            reusableBmp = null;
        }
        super.onDestroy();
    }

    /** 本服务为启动型服务，不支持绑定 */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ====================================================================
     *  主题计算
     * ==================================================================== */

    /**
     * 计算主题因子 t∈[0,1]
     * 00:00→12:00 线性 0→1，12:00→24:00 线性 1→0
     * 12:00 → 1.0（白天淡蓝峰值）
     * 00:00 → 0.0（纯黑夜谷值）
     * 06:00 → 0.5，18:00 → 0.5
     *
     * 与 app 内 index.html 的 computeThemeFactor 完全一致
     */
    private static float computeThemeFactor(int hours, int minutes, int seconds) {
        float h = hours + minutes / 60f + seconds / 3600f;
        if (h <= 12f) {
            return h / 12f;
        }
        return (24f - h) / 12f;
    }

    /**
     * 颜色线性插值（按 ARGB 各通道分别插值后重组）
     *
     * @param a 起点颜色（t=0 时的值，对应 NIGHT）
     * @param b 终点颜色（t=1 时的值，对应 DAY）
     * @param t 插值因子 [0,1]
     * @return 插值后的 ARGB 颜色
     */
    private static int lerpColor(int a, int b, float t) {
        int aA = Color.alpha(a), aR = Color.red(a), aG = Color.green(a), aB = Color.blue(a);
        int bA = Color.alpha(b), bR = Color.red(b), bG = Color.green(b), bB = Color.blue(b);
        return Color.argb(
                Math.round(aA + (bA - aA) * t),
                Math.round(aR + (bR - aR) * t),
                Math.round(aG + (bG - aG) * t),
                Math.round(aB + (bB - aB) * t));
    }

    /**
     * 数值线性插值
     */
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * 根据主题因子 t 计算当前调色板
     * t=1 取 DAY，t=0 取 NIGHT，其余线性插值。结果写入复用对象 currentPalette。
     */
    private void resolvePalette(float t) {
        Palette p = currentPalette;
        p.bg = lerpColor(N_BG, D_BG, t);
        p.bgSoft = lerpColor(N_BG_SOFT, D_BG_SOFT, t);
        p.bgDeep = lerpColor(N_BG_DEEP, D_BG_DEEP, t);
        p.surface = lerpColor(N_SURFACE, D_SURFACE, t);
        p.surfaceSoft = lerpColor(N_SURFACE_SOFT, D_SURFACE_SOFT, t);
        p.surfaceDeep = lerpColor(N_SURFACE_DEEP, D_SURFACE_DEEP, t);
        p.blue1 = lerpColor(N_BLUE1, D_BLUE1, t);
        p.blue2 = lerpColor(N_BLUE2, D_BLUE2, t);
        p.blue3 = lerpColor(N_BLUE3, D_BLUE3, t);
        p.blue4 = lerpColor(N_BLUE4, D_BLUE4, t);
        p.ink = lerpColor(N_INK, D_INK, t);
        p.inkSoft = lerpColor(N_INK_SOFT, D_INK_SOFT, t);
        p.shLight = lerpColor(N_SH_LIGHT, D_SH_LIGHT, t);
        p.shLightSoft = lerpColor(N_SH_LIGHT_SOFT, D_SH_LIGHT_SOFT, t);
        p.shDark = lerpColor(N_SH_DARK, D_SH_DARK, t);
        p.shDarkSoft = lerpColor(N_SH_DARK_SOFT, D_SH_DARK_SOFT, t);
        p.lightDx = lerp(N_LIGHT_DX, D_LIGHT_DX, t);
        p.lightDy = lerp(N_LIGHT_DY, D_LIGHT_DY, t);
    }

    /* ====================================================================
     *  主绘制流程
     * ==================================================================== */

    /**
     * 绘制一帧时钟到 reusableBmp
     *
     * 绘制顺序（均在圆角矩形裁剪区内）：
     *   1. 擦除画布为透明
     *   2. 平移防烧屏偏移，裁剪为圆角矩形
     *   3. 背景：基底色 + 左上高光径向 + 右下深色径向
     *   4. 表盘：凸起阴影 + 径向渐变表面
     *   5. 时环（外）：12 刻度 + 12/3/6/9 数字
     *   6. 分环（中）：60 刻度 + 0/10/20/30/40/50 数字
     *   7. 秒环（内）：60 刻度 + 0/30 数字
     *   8. 固定指针（指向 12 点）
     *   9. 中心点
     */
    private void drawClock() {
        // 取当前时间（含毫秒，保证指针平滑）
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        int s = cal.get(Calendar.SECOND);
        int ms = cal.get(Calendar.MILLISECOND);

        // 计算主题因子并解析当前调色板
        float t = computeThemeFactor(h, m, s);
        resolvePalette(t);
        Palette p = currentPalette;

        Canvas c = reusableCanvas;
        // 步骤 1：擦除为透明（复用位图，每帧清空）
        reusableBmp.eraseColor(0);

        // 防烧屏偏移（8 步循环）
        int[] off = BURN_IN_OFFSETS[burnStep];
        burnStep = (burnStep + 1) % BURN_IN_OFFSETS.length;

        // 基础几何参数
        final float cx = SIZE / 2f;
        final float cy = SIZE / 2f;
        final float R = SIZE / 2f - 8f; // 表盘基础半径（圆角矩形半边长）

        // 步骤 2：保存画布、平移防烧屏偏移、裁剪圆角矩形
        int sc = c.save();
        c.translate(off[0], off[1]);
        reusablePath.reset();
        reusableRect.set(8f, 8f, SIZE - 8f, SIZE - 8f);
        reusablePath.addRoundRect(reusableRect, CORNER_RADIUS, CORNER_RADIUS, Path.Direction.CW);
        c.clipPath(reusablePath);

        // 步骤 3：背景
        drawBackground(c, p);

        // 步骤 4：表盘圆（半径 R*0.92）
        float dialR = R * 0.92f;
        drawDial(c, cx, cy, dialR, p);

        // 步骤 5：时环（外环，半径 R*0.86）
        float hourR = R * 0.86f;
        // 时环旋转角度：每小时 30°，取负实现逆时针旋转
        float hourAngle = -((h % 12) + m / 60f + s / 3600f) * 30f;
        drawHourRing(c, cx, cy, hourR, hourAngle, p);

        // 步骤 6：分环（中环，半径 R*0.60）
        float minR = R * 0.60f;
        float minAngle = -(m + s / 60f + ms / 60000f) * 6f;
        drawMinuteRing(c, cx, cy, minR, minAngle, p);

        // 步骤 7：秒环（内环，半径 R*0.36）
        float secR = R * 0.36f;
        float secAngle = -(s + ms / 1000f) * 6f;
        drawSecondRing(c, cx, cy, secR, secAngle, p);

        // 步骤 8：固定指针（指向 12 点方向）
        drawHand(c, cx, cy, R * 0.022f, R * 0.82f, p);

        // 步骤 9：中心点
        drawCenterDot(c, cx, cy, R * 0.032f, p);

        // 恢复画布（撤销平移与裁剪）
        c.restoreToCount(sc);
    }

    /* ====================================================================
     *  绘制子方法
     * ==================================================================== */

    /**
     * 绘制圆角矩形背景
     * 基底 bg 填充 + 左上 bg-soft 高光径向 + 右下 bg-deep 深色径向，
     * 模拟 app body 的双向径向渐变背景
     */
    private void drawBackground(Canvas c, Palette p) {
        RectF rr = reusableRect;
        rr.set(8f, 8f, SIZE - 8f, SIZE - 8f);
        Paint pt = paintA;
        pt.setStyle(Paint.Style.FILL);
        pt.setMaskFilter(null);

        // 基底色
        pt.setShader(null);
        pt.setColor(p.bg);
        c.drawRoundRect(rr, CORNER_RADIUS, CORNER_RADIUS, pt);

        // 左上高光（bg-soft，中心约在 30%/20%）
        pt.setShader(new RadialGradient(
                SIZE * 0.3f, SIZE * 0.2f, SIZE * 0.6f,
                p.bgSoft, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(rr, CORNER_RADIUS, CORNER_RADIUS, pt);

        // 右下深色（bg-deep，中心约在 70%/80%）
        pt.setShader(new RadialGradient(
                SIZE * 0.7f, SIZE * 0.8f, SIZE * 0.6f,
                p.bgDeep, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(rr, CORNER_RADIUS, CORNER_RADIUS, pt);

        pt.setShader(null);
    }

    /**
     * 绘制表盘圆
     * 先画凸起阴影（新拟态），再画径向渐变表面（surface-soft → surface → surface-deep）
     * 高光中心位于左上 35%/30%，与光源方向一致
     */
    private void drawDial(Canvas c, float cx, float cy, float r, Palette p) {
        // 凸起阴影（光源自左上，阴影偏移取光源的 0.6 倍）
        drawRaisedShadow(c, cx, cy, r, p, p.lightDx * 0.6f, p.lightDy * 0.6f);

        // 表盘表面径向渐变
        Paint pt = paintA;
        pt.setStyle(Paint.Style.FILL);
        pt.setMaskFilter(null);
        // 高光中心：表盘左上 35%/30% 位置
        float gx = cx - r * 0.3f;
        float gy = cy - r * 0.4f;
        pt.setShader(new RadialGradient(
                gx, gy, r * 1.4f,
                new int[]{p.surfaceSoft, p.surface, p.surfaceDeep},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, pt);
        pt.setShader(null);
    }

    /**
     * 绘制凸起阴影（新拟态双阴影）
     * 在圆的右下偏移处画暗影 shDark，左上偏移处画高光 shLight，
     * 配合模糊滤镜产生柔和的凸起效果
     *
     * @param dx 阴影横向偏移（取正值，暗影在右下、高光在左上）
     * @param dy 阴影纵向偏移
     */
    private void drawRaisedShadow(Canvas c, float cx, float cy, float r,
                                  Palette p, float dx, float dy) {
        Paint pt = paintB;
        pt.setStyle(Paint.Style.FILL);
        pt.setMaskFilter(blurFilter);

        // 右下暗影（背离光源方向）
        pt.setColor(p.shDark);
        c.drawCircle(cx + dx, cy + dy, r, pt);

        // 左上高光（朝向光源方向）
        pt.setColor(p.shLight);
        c.drawCircle(cx - dx, cy - dy, r, pt);

        pt.setMaskFilter(null);
    }

    /**
     * 绘制内凹环阴影
     * 在环半径内侧画一圈柔和的暗影 shDarkSoft，模拟刻度环凹陷的边缘
     */
    private void drawInsetRing(Canvas c, float cx, float cy, float r, Palette p) {
        Paint pt = paintB;
        float stroke = r * 0.04f;
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(stroke);
        pt.setMaskFilter(blurFilter);
        pt.setColor(p.shDarkSoft);
        c.drawCircle(cx, cy, r - stroke * 0.5f, pt);
        pt.setMaskFilter(null);
        pt.setStyle(Paint.Style.FILL);
    }

    /**
     * 绘制时环（外环）
     * 12 个刻度（3/6/9/12 位置为主刻度，INK 色；其余为次刻度，BLUE4 色）
     * 数字仅 12/3/6/9，INK 加粗
     * 整环随 hourAngle 逆时针旋转
     */
    private void drawHourRing(Canvas c, float cx, float cy, float r,
                              float ringAngle, Palette p) {
        drawInsetRing(c, cx, cy, r, p);
        int sc = c.save();
        c.rotate(ringAngle, cx, cy);
        // 12 刻度（每 3 个为主刻度，对应 12/3/6/9 位置）
        drawTicks(c, cx, cy, r, 12, 3, p.ink, p.blue4,
                r * 0.05f, r * 0.025f, r * 0.012f, r * 0.006f);
        // 数字 12/3/6/9，对应角度 0/90/180/270（0=顶部）
        String[] labels = {"12", "3", "6", "9"};
        float[] angles = {0f, 90f, 180f, 270f};
        drawNumbers(c, cx, cy, r - r * 0.10f, ringAngle,
                labels, angles, p.ink, r * 0.11f, true);
        c.restoreToCount(sc);
    }

    /**
     * 绘制分环（中环）
     * 60 个刻度（每 5 个为主刻度，INK 色；其余为次刻度，BLUE4 色）
     * 数字 0/10/20/30/40/50，INK_SOFT
     * 整环随 minAngle 逆时针旋转
     */
    private void drawMinuteRing(Canvas c, float cx, float cy, float r,
                                float ringAngle, Palette p) {
        drawInsetRing(c, cx, cy, r, p);
        int sc = c.save();
        c.rotate(ringAngle, cx, cy);
        // 60 刻度（每 5 个为主刻度）
        drawTicks(c, cx, cy, r, 60, 5, p.ink, p.blue4,
                r * 0.06f, r * 0.03f, r * 0.010f, r * 0.005f);
        // 数字 0/10/20/30/40/50，对应角度 0/60/120/180/240/300
        String[] labels = {"0", "10", "20", "30", "40", "50"};
        float[] angles = {0f, 60f, 120f, 180f, 240f, 300f};
        drawNumbers(c, cx, cy, r - r * 0.12f, ringAngle,
                labels, angles, p.inkSoft, r * 0.10f, false);
        c.restoreToCount(sc);
    }

    /**
     * 绘制秒环（内环）
     * 60 个刻度（每 5 个为主刻度，BLUE4 色；其余为次刻度，BLUE3 色）
     * 数字仅 0 和 30，INK_SOFT
     * 整环随 secAngle 逆时针旋转
     */
    private void drawSecondRing(Canvas c, float cx, float cy, float r,
                                float ringAngle, Palette p) {
        drawInsetRing(c, cx, cy, r, p);
        int sc = c.save();
        c.rotate(ringAngle, cx, cy);
        // 60 刻度（每 5 个为主刻度）
        drawTicks(c, cx, cy, r, 60, 5, p.blue4, p.blue3,
                r * 0.05f, r * 0.025f, r * 0.008f, r * 0.004f);
        // 数字 0/30，对应角度 0/180
        String[] labels = {"0", "30"};
        float[] angles = {0f, 180f};
        drawNumbers(c, cx, cy, r - r * 0.14f, ringAngle,
                labels, angles, p.inkSoft, r * 0.09f, false);
        c.restoreToCount(sc);
    }

    /**
     * 绘制刻度环
     * 在半径 r 的圆周上等分 count 个刻度；i % majorEvery == 0 为主刻度（更长更粗），
     * 否则为次刻度。角度 0 位于顶部（12 点方向），顺时针递增。
     *
     * @param majorColor 主刻度颜色
     * @param minorColor 次刻度颜色
     * @param majorLen   主刻度长度
     * @param minorLen   次刻度长度
     * @param majorW     主刻度线宽
     * @param minorW     次刻度线宽
     */
    private void drawTicks(Canvas c, float cx, float cy, float r, int count, int majorEvery,
                           int majorColor, int minorColor,
                           float majorLen, float minorLen, float majorW, float minorW) {
        Paint pt = paintC;
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeCap(Paint.Cap.ROUND);
        pt.setMaskFilter(null);
        double step = 360.0 / count;
        for (int i = 0; i < count; i++) {
            // 角度 0 位于顶部：减 90° 使 0 指向正上方
            double ang = Math.toRadians(i * step - 90.0);
            float cos = (float) Math.cos(ang);
            float sin = (float) Math.sin(ang);
            boolean major = (i % majorEvery == 0);
            pt.setColor(major ? majorColor : minorColor);
            pt.setStrokeWidth(major ? majorW : minorW);
            float len = major ? majorLen : minorLen;
            // 从外圆周向内绘制
            c.drawLine(cx + cos * r, cy + sin * r,
                    cx + cos * (r - len), cy + sin * (r - len), pt);
        }
    }

    /**
     * 绘制数字（保持正立）
     * 数字随环旋转（位置由 ringAngle 决定），但文字本身始终正立。
     *
     * 算法：
     *   1. 当前画布已由调用方 rotate(ringAngle) 旋转
     *   2. 在旋转后的坐标系中按角度 a 计算数字位置 (tx, ty)
     *      （a=0 表示顶部/12 点方向：rad = (a-90)°）
     *   3. 在 (tx, ty) 处反向旋转 -ringAngle，使文字恢复正立
     *   4. drawText 绘制
     *
     * @param textR    数字所在半径
     * @param ringAngle 当前环旋转角度（度）
     * @param angles   每个数字对应的角度（0=顶部，顺时针）
     */
    private void drawNumbers(Canvas c, float cx, float cy, float textR, float ringAngle,
                             String[] labels, float[] angles, int color,
                             float textSize, boolean bold) {
        Paint pt = paintD;
        pt.setColor(color);
        pt.setTextSize(textSize);
        pt.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        pt.setTextAlign(Paint.Align.CENTER);
        pt.setMaskFilter(null);
        // 垂直居中补偿：使文字几何中心落在 (tx, ty)
        Paint.FontMetrics fm = pt.getFontMetrics();
        float textOffset = -(fm.ascent + fm.descent) / 2f;

        for (int i = 0; i < labels.length; i++) {
            float a = angles[i];
            // a=0 → 顶部：rad = (a-90)°，使 cos/sin 指向正上方
            float rad = (float) Math.toRadians(a - 90f);
            float tx = cx + (float) Math.cos(rad) * textR;
            float ty = cy + (float) Math.sin(rad) * textR;
            int sc = c.save();
            // 反向自旋 ringAngle，抵消环旋转，保持文字正立
            c.rotate(-ringAngle, tx, ty);
            c.drawText(labels[i], tx, ty + textOffset, pt);
            c.restoreToCount(sc);
        }
    }

    /**
     * 绘制固定指针（指向 12 点方向）
     * 由四层组成：
     *   1. 偏移阴影线（shDark + 模糊）：模拟指针投影
     *   2. 主体（BLUE4 圆角矩形）
     *   3. 左侧高光（白色 0x50）：模拟受光面
     *   4. 右侧暗边（BLUE2）：模拟背光面
     *
     * @param width  指针宽度
     * @param length 指针长度（从中心向 12 点方向延伸）
     */
    private void drawHand(Canvas c, float cx, float cy, float width, float length, Palette p) {
        float half = width / 2f;
        Paint pt = paintE;
        pt.setStyle(Paint.Style.FILL);
        RectF rr = reusableRect;

        // 1. 偏移阴影线（投影，向右下偏移光源的 0.3 倍）
        pt.setMaskFilter(blurFilter);
        pt.setColor(p.shDark);
        float sx = p.lightDx * 0.3f;
        float sy = p.lightDy * 0.3f;
        rr.set(cx - half + sx, cy - length + sy, cx + half + sx, cy + sy);
        c.drawRoundRect(rr, half, half, pt);
        pt.setMaskFilter(null);

        // 2. 主体（钢蓝）
        pt.setColor(p.blue4);
        rr.set(cx - half, cy - length, cx + half, cy);
        c.drawRoundRect(rr, half, half, pt);

        // 3. 左侧高光（白色 0x50，受光面）
        pt.setColor(0x50FFFFFF);
        rr.set(cx - half, cy - length, cx - half + width * 0.28f, cy);
        c.drawRoundRect(rr, width * 0.14f, width * 0.14f, pt);

        // 4. 右侧暗边（BLUE2，背光面）
        pt.setColor(p.blue2);
        rr.set(cx + half - width * 0.28f, cy - length, cx + half, cy);
        c.drawRoundRect(rr, width * 0.14f, width * 0.14f, pt);
    }

    /**
     * 绘制中心点
     * 径向渐变（surface-soft → blue3）+ 顶部白色高光，作为指针的固定轴心
     */
    private void drawCenterDot(Canvas c, float cx, float cy, float r, Palette p) {
        Paint pt = paintA;
        pt.setStyle(Paint.Style.FILL);
        pt.setMaskFilter(null);
        // 径向渐变：中心 surface-soft，边缘 blue3
        pt.setShader(new RadialGradient(
                cx, cy - r * 0.3f, r * 1.3f,
                p.surfaceSoft, p.blue3, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, pt);
        pt.setShader(null);

        // 顶部白色高光（模拟金属球面反光）
        pt.setColor(0x80FFFFFF);
        c.drawCircle(cx - r * 0.25f, cy - r * 0.3f, r * 0.35f, pt);
    }
}
