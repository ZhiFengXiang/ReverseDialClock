/*
 * ========================================================================
 * 反转表盘时钟 - 桌面小组件提供者（AppWidgetProvider）
 * ========================================================================
 *
 * 功能说明：
 *   管理桌面小组件的生命周期和自动更新机制。
 *   使用 AlarmManager 定时触发更新，通过 ClockRenderService 渲染时钟位图。
 *
 * 工作流程：
 *   1. 用户添加小组件 → onEnabled() → 启动 AlarmManager 定时任务
 *   2. 定时触发 → onReceive() → 启动 ClockRenderService 重绘
 *   3. 系统调度更新 → onUpdate() → 启动 ClockRenderService 重绘
 *   4. 用户移除小组件 → onDisabled() → 取消 AlarmManager 定时任务
 *
 * 更新间隔：1 秒（秒针走动可见，与 app 内时钟同步）
 * ========================================================================
 */
package com.clock.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * 桌面小组件提供者
 * 负责小组件的创建、更新、销毁和定时刷新。实际渲染由 ClockRenderService 完成。
 */
public class ClockWidgetProvider extends AppWidgetProvider {

    /** 自定义 Action：定时更新广播 */
    private static final String ACTION_AUTO_UPDATE = "com.clock.app.WIDGET_UPDATE";

    /** 自动更新间隔：1 秒（秒针走动可见） */
    private static final long UPDATE_INTERVAL = 1_000;

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        startAutoUpdate(context);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        stopAutoUpdate(context);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            requestRender(context, appWidgetManager, widgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_AUTO_UPDATE.equals(intent.getAction())) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, ClockWidgetProvider.class));
            for (int id : ids) {
                requestRender(context, mgr, id);
            }
        }
    }

    /** 请求渲染服务更新指定小组件 */
    private void requestRender(Context context, AppWidgetManager mgr, int widgetId) {
        Intent intent = new Intent(context, ClockRenderService.class);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        context.startService(intent);
    }

    /**
     * 启动 AlarmManager 定时更新
     * 使用 ELAPSED_REALTIME（不唤醒设备），每 1 秒发送一次广播。
     * 屏幕息屏时系统会节流，可接受（小组件不可见）。
     */
    private void startAutoUpdate(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ClockWidgetProvider.class);
        intent.setAction(ACTION_AUTO_UPDATE);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + UPDATE_INTERVAL,
                UPDATE_INTERVAL, pi);
    }

    /** 取消 AlarmManager 定时更新 */
    private void stopAutoUpdate(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ClockWidgetProvider.class);
        intent.setAction(ACTION_AUTO_UPDATE);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }
}
