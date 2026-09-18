package com.kaoyan28.miao.widgets;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class MathWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        // 走 refreshOne：它是 ListView 组件，除了 updateAppWidget 还要 notify 一次数据变更
        WidgetRender.refreshOne(c, WidgetRender.T_MATH);
    }
}
