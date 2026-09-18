package com.kaoyan28.miao.widgets;

import com.kaoyan28.miao.R;

import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 「今日计划」组件的列表数据源。
 *
 * RemoteViews 里唯一能滚动的视图是集合类视图（ListView / GridView ...），
 * 而 ScrollView 不在 RemoteViews 白名单里 —— 所以计划一多就会被裁掉。
 * 这里用 ListView + RemoteViewsService 让整张计划表可以上下滑动。
 *
 * 点击：集合行不能直接用 setOnClickPendingIntent，必须由 provider 端
 * setPendingIntentTemplate + 行内 setOnClickFillInIntent 组合（见 WidgetRender.renderPlan）。
 */
public class PlanListWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new PlanFactory(getApplicationContext());
    }

    static class PlanFactory implements RemoteViewsService.RemoteViewsFactory {
        private final Context ctx;
        private final List<JSONObject> items = new ArrayList<>();

        PlanFactory(Context c) {
            this.ctx = c;
        }

        @Override
        public void onCreate() {
            // no-op
        }

        @Override
        public void onDataSetChanged() {
            items.clear();
            try {
                JSONObject snap = WidgetRender.parse(Store.readSnapshot(ctx));
                JSONArray plan = snap.optJSONArray("plan");
                if (plan == null) return;
                for (int i = 0; i < plan.length(); i++) {
                    JSONObject it = plan.optJSONObject(i);
                    if (it != null) items.add(it);
                }
            } catch (Exception ignore) {
            }
        }

        @Override
        public void onDestroy() {
            items.clear();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews row = new RemoteViews(ctx.getPackageName(), R.layout.widget_plan_row);
            if (position < 0 || position >= items.size()) return row;
            JSONObject it = items.get(position);
            boolean done = it.optBoolean("done", false);
            String time = it.optString("time", "");

            row.setTextViewText(R.id.row_check, done ? "\u2611" : "\u2610");
            row.setTextViewText(R.id.row_time, time);
            row.setTextViewText(R.id.row_text, it.optString("text", ""));
            row.setTextColor(R.id.row_check, done ? WidgetRender.C_DONE : WidgetRender.C_ACCENT);
            row.setTextColor(R.id.row_time, WidgetRender.C_MUTED);
            row.setTextColor(R.id.row_text, done ? WidgetRender.C_DONE : WidgetRender.C_BODY);
            int f = Paint.STRIKE_THRU_TEXT_FLAG | Paint.ANTI_ALIAS_FLAG;
            row.setInt(R.id.row_text, "setPaintFlags", done ? f : Paint.ANTI_ALIAS_FLAG);

            Intent fill = new Intent();
            fill.putExtra("extra", "{\"pid\":" + JSONObject.quote(it.optString("id", "")) + "}");
            row.setOnClickFillInIntent(R.id.row_root, fill);
            row.setOnClickFillInIntent(R.id.row_check, fill);
            return row;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }
}
