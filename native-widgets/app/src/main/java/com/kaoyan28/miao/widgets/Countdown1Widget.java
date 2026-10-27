package com.kaoyan28.miao.widgets;

import com.kaoyan28.miao.R;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

/**
 * 倒数日（1 个）—— 2×2 小尺寸组件：只显示一个倒数日，天数用大号字突出。
 * 显示哪一个是 App 内「倒数日 → 🖥️ 桌面组件显示设置」里选的（未选则自动取列表第一个）。
 */
public class Countdown1Widget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews rv = WidgetRender.render(c, WidgetRender.T_CD1, Store.readSnapshot(c), Store.readState(c));
        for (int id : ids) m.updateAppWidget(id, rv);
    }
}
