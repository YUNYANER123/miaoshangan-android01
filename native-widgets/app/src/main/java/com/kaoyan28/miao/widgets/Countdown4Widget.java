package com.kaoyan28.miao.widgets;

import com.kaoyan28.miao.R;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

/**
 * 倒数日（4 个）—— 4×3 组件：四行，各显示一个倒数日。
 * 显示哪四个是 App 内「倒数日 → 🖥️ 桌面组件显示设置」里选的（未选则自动取列表前四个）。
 */
public class Countdown4Widget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews rv = WidgetRender.render(c, WidgetRender.T_CD4, Store.readSnapshot(c), Store.readState(c));
        for (int id : ids) m.updateAppWidget(id, rv);
    }
}
