package com.kaoyan28.miao.widgets;

import com.kaoyan28.miao.R;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 「随机拼写」组件的列表数据源。
 *
 * 组件里释义可能很长，而 RemoteViews 不支持 ScrollView，只有集合类视图
 * （ListView）能在桌面组件里上下滑动。所以把「释义 + 已拼写 + 结果」这一块
 * 交给本服务逐行渲染，长释义就能滚动查看，不会再把下面的键盘按钮顶没。
 *
 * 这一行是纯展示、不需要点击，因此不需要 fill-in Intent / PendingIntent 模板。
 */
public class SpellListWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new SpellFactory(getApplicationContext());
    }

    static class SpellFactory implements RemoteViewsService.RemoteViewsFactory {
        private final Context ctx;
        private String meaning = "";

        SpellFactory(Context c) {
            this.ctx = c;
        }

        @Override
        public void onCreate() {
            // no-op
        }

        @Override
        public void onDataSetChanged() {
            meaning = "";
            try {
                JSONObject snap = WidgetRender.parse(Store.readSnapshot(ctx));
                JSONObject st = WidgetRender.parseState(Store.readState(ctx));
                JSONArray pool = snap.optJSONArray("spellPool");
                if (pool == null || pool.length() == 0) return;
                int idx = Math.max(0, Math.min(st.optInt("spellIdx", 0), pool.length() - 1));
                JSONObject w = pool.optJSONObject(idx);
                if (w == null) w = pool.optJSONObject(0);
                meaning = w != null ? w.optString("meaning", "") : "";
            } catch (Exception ignore) {
            }
        }

        @Override
        public void onDestroy() {
            meaning = "";
            typed = "";
            res = "";
        }

        @Override
        public int getCount() {
            return 1;
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews r = new RemoteViews(ctx.getPackageName(), R.layout.widget_spell_list_row);
            r.setTextViewText(R.id.s_mean, meaning);
            r.setTextColor(R.id.s_mean, WidgetRender.C_BODY);
            return r;
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
