package com.kaoyan28.miao.widgets;

import com.kaoyan28.miao.R;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「数学今日题」组件的列表数据源。
 *
 * 组件里只保留选择题（桌面 RemoteViews 放不了 EditText，填空题没法填），
 * 所以一屏就是：题干 + A/B/C/D 四个选项按钮 + （答完后）解析 + 下一题。
 * 用 ListView 渲染的好处是题干/解析再长也能滑动查看，不会被裁掉。
 *
 * 点击同样走 fill-in Intent：真正的动作写在 extra JSON 的 "act" 字段里
 * （PendingIntent 模板只能固定一个 action）。
 */
public class MathListWidgetService extends RemoteViewsService {

    static final int ROW_HEAD = 0;
    static final int ROW_OPT = 1;
    static final int ROW_ANS = 2;
    static final int ROW_NEXT = 3;

    private static final Pattern PICK_LETTER = Pattern.compile("选\\s*[:：]?\\s*([A-Da-d])");

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new MathFactory(getApplicationContext());
    }

    /** 正确答案的下标（0=A）；拿不到返回 -1。 */
    static int correctOf(JSONObject q) {
        if (q == null) return -1;
        String k = q.optString("k", "").trim();
        if (k.length() >= 1) {
            int i = "ABCD".indexOf(Character.toUpperCase(k.charAt(0)));
            if (i >= 0) return i;
        }
        Matcher m = PICK_LETTER.matcher(q.optString("a", ""));
        if (m.find()) {
            int i = "ABCD".indexOf(Character.toUpperCase(m.group(1).charAt(0)));
            if (i >= 0) return i;
        }
        return -1;
    }

    static Intent fillPick(int sel) {
        Intent i = new Intent();
        i.putExtra("extra", "{\"act\":\"pick\",\"sel\":" + sel + "}");
        return i;
    }

    static Intent fillNext() {
        Intent i = new Intent();
        i.putExtra("extra", "{\"act\":\"next\"}");
        return i;
    }

    static class MathFactory implements RemoteViewsService.RemoteViewsFactory {
        private final Context ctx;
        private final List<int[]> rows = new ArrayList<>();
        private JSONObject cur;
        private int total;
        private int qi;
        private int nOpts;
        private boolean answered;
        private int sel = -1;
        private int correct = -1;

        MathFactory(Context c) {
            this.ctx = c;
        }

        @Override
        public void onCreate() {
            // no-op
        }

        @Override
        public void onDataSetChanged() {
            rows.clear();
            cur = null;
            total = 0;
            nOpts = 0;
            answered = false;
            sel = -1;
            correct = -1;
            try {
                JSONObject snap = WidgetRender.parse(Store.readSnapshot(ctx));
                JSONObject st = WidgetRender.parseState(Store.readState(ctx));
                JSONArray math = snap.optJSONArray("math");
                total = math != null ? math.length() : 0;
                if (total <= 0) return;
                qi = Math.max(0, Math.min(st.optInt("mathQi", 0), total - 1));
                cur = math.optJSONObject(qi);
                if (cur == null) cur = math.optJSONObject(0);
                answered = st.optBoolean("mathAnswered", false);
                sel = st.optInt("mathSel", -1);
                correct = correctOf(cur);
                JSONArray opts = cur != null ? cur.optJSONArray("opts") : null;
                nOpts = opts != null ? Math.min(4, opts.length()) : 0;

                rows.add(new int[]{ROW_HEAD, 0});
                for (int j = 0; j < nOpts; j++) rows.add(new int[]{ROW_OPT, j});
                if (answered) rows.add(new int[]{ROW_ANS, 0});
                rows.add(new int[]{ROW_NEXT, 0});
            } catch (Exception ignore) {
            }
        }

        @Override
        public void onDestroy() {
            rows.clear();
            cur = null;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews r = new RemoteViews(ctx.getPackageName(), R.layout.widget_math_row);
            if (position < 0 || position >= rows.size() || cur == null) return r;
            int[] spec = rows.get(position);
            try {
                // 一份布局被四种行共用，而 ListView 会复用视图 ——
                // 先全部收起再按行类型打开需要的控件，否则会出现上一行的残留。
                r.setViewVisibility(R.id.mr_counter, android.view.View.GONE);
                r.setViewVisibility(R.id.mr_stem, android.view.View.GONE);
                r.setViewVisibility(R.id.mr_src, android.view.View.GONE);
                r.setViewVisibility(R.id.mr_b, android.view.View.GONE);
                r.setViewVisibility(R.id.mr_ans, android.view.View.GONE);
                r.setViewVisibility(R.id.mr_next, android.view.View.GONE);
                switch (spec[0]) {
                    case ROW_HEAD: {
                        r.setViewVisibility(R.id.mr_counter, android.view.View.VISIBLE);
                        r.setViewVisibility(R.id.mr_stem, android.view.View.VISIBLE);
                        String sec = cur.optString("sec", "");
                        String tag = sec.isEmpty() ? "选择题" : sec + "·选择题";
                        r.setTextViewText(R.id.mr_counter, tag + "  " + (qi + 1) + " / " + total);
                        r.setTextColor(R.id.mr_counter, WidgetRender.C_ACCENT);
                        String stem = cur.optString("stem", "");
                        if (stem.isEmpty()) stem = cur.optString("q", "");
                        r.setTextViewText(R.id.mr_stem, stem);
                        r.setTextColor(R.id.mr_stem, WidgetRender.C_BODY);
                        String src = cur.optString("src", "");
                        if (!src.isEmpty() && !answered) {
                            r.setViewVisibility(R.id.mr_src, android.view.View.VISIBLE);
                            r.setTextViewText(R.id.mr_src, "题源：" + src);
                            r.setTextColor(R.id.mr_src, WidgetRender.C_MUTED);
                        }
                        break;
                    }
                    case ROW_OPT: {
                        int j = spec[1];
                        JSONArray opts = cur.optJSONArray("opts");
                        String txt = opts != null ? opts.optString(j, "") : "";
                        r.setViewVisibility(R.id.mr_b, android.view.View.VISIBLE);
                        r.setTextViewText(R.id.mr_b, "ABCD".charAt(j) + ". " + txt);
                        if (!answered) {
                            r.setTextColor(R.id.mr_b, WidgetRender.C_WHITE);
                            r.setInt(R.id.mr_b, "setBackgroundColor", 0xFF2F80ED);
                            r.setOnClickFillInIntent(R.id.mr_b, fillPick(j));
                        } else if (j == correct) {
                            r.setTextColor(R.id.mr_b, WidgetRender.C_WHITE);
                            r.setInt(R.id.mr_b, "setBackgroundColor", 0xFF2E9E5B);
                        } else if (j == sel) {
                            r.setTextColor(R.id.mr_b, WidgetRender.C_WHITE);
                            r.setInt(R.id.mr_b, "setBackgroundColor", 0xFFE5476A);
                        } else {
                            r.setTextColor(R.id.mr_b, WidgetRender.C_BODY);
                            r.setInt(R.id.mr_b, "setBackgroundColor", 0xFFD6E6FF);
                        }
                        break;
                    }
                    case ROW_ANS: {
                        boolean ok = correct >= 0 && sel == correct;
                        r.setViewVisibility(R.id.mr_ans, android.view.View.VISIBLE);
                        StringBuilder sb = new StringBuilder(ok ? "✅ 答对！" : "❌ 答错。");
                        if (correct >= 0) {
                            sb.append("正确答案：").append("ABCD".charAt(correct));
                        }
                        String sTxt = cur.optString("s", "");
                        if (sTxt.length() > 160) sTxt = sTxt.substring(0, 160) + "…";
                        if (!sTxt.isEmpty()) sb.append("\n解析：").append(sTxt);
                        r.setTextViewText(R.id.mr_ans, sb.toString());
                        r.setTextColor(R.id.mr_ans, ok ? 0xFF2E9E5B : 0xFFE5476A);
                        break;
                    }
                    case ROW_NEXT: {
                        r.setViewVisibility(R.id.mr_next, android.view.View.VISIBLE);
                        r.setTextViewText(R.id.mr_next, answered ? "下一题 →" : "跳过此题 →");
                        r.setTextColor(R.id.mr_next, WidgetRender.C_WHITE);
                        r.setInt(R.id.mr_next, "setBackgroundColor", 0xFF2F80ED);
                        r.setOnClickFillInIntent(R.id.mr_next, fillNext());
                        break;
                    }
                    default:
                        break;
                }
            } catch (Exception ignore) {
            }
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
