package com.kaoyan28.miao.widgets;

import com.kaoyan28.miao.R;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

public class MajorQuizWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews rv = WidgetRender.render(c, WidgetRender.T_MAJQ, Store.readSnapshot(c), Store.readState(c));
        for (int id : ids) m.updateAppWidget(id, rv);
    }
}
