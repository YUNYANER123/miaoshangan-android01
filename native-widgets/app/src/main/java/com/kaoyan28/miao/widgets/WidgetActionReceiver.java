package com.kaoyan28.miao.widgets;

import com.kaoyan28.miao.R;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Receives all widget interactions (ACTION_DO), the periodic/refresh broadcast
 * (ACTION_REFRESH) and device boot (BOOT_COMPLETED).
 *
 * Two kinds of mutations:
 *  - optimistic: directly flip the snapshot file (plan done / life) so the
 *    widget shows immediate feedback; the matching action is also queued so
 *    the web app can reconcile store + re-push.
 *  - queued only: words/math/major actions are recorded for the app; the
 *    widget keeps its own local UI state (index / reveal / answered).
 */
public class WidgetActionReceiver extends BroadcastReceiver {
    static final String ACTION_DO = "com.kaoyan28.miao.ACTION_WIDGET_DO";
    static final String ACTION_REFRESH = "com.kaoyan28.miao.ACTION_REFRESH";
    static final String ACTION_BOOT = "android.intent.action.BOOT_COMPLETED";
    private static final String TAG = "KaoyanWidget";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            WidgetRender.refreshAll(ctx);
            return;
        }
        if (ACTION_BOOT.equals(action)) {
            WidgetRender.refreshAll(ctx);
            return;
        }
        if (!ACTION_DO.equals(action)) return;

        String type = intent.getStringExtra("type");
        String act = intent.getStringExtra("action");
        String extra = intent.getStringExtra("extra");
        if (type == null || act == null) return;

        // ListView 行的点击走 fill-in Intent，而 PendingIntent 模板只能固定一个 action ——
        // 真正的动作由行内写进 extra JSON 的 "act" 字段，这里用它覆盖模板的占位 action。
        JSONObject e0 = ex(extra);
        if (e0.has("act")) act = e0.optString("act", act);

        try {
            JSONObject state = WidgetRender.parseState(Store.readState(ctx));
            String snapStr = Store.readSnapshot(ctx);
            JSONObject snap = WidgetRender.parse(snapStr);

            switch (type) {
                case WidgetRender.T_PLAN:
                    handlePlan(ctx, snap, state, act, extra);
                    break;
                case WidgetRender.T_LIFE:
                    handleLife(ctx, snap, state, act, extra);
                    break;
                case WidgetRender.T_WORDS:
                    handleWords(ctx, state, act, extra);
                    break;
                case WidgetRender.T_SPELL:
                    handleSpell(ctx, state, act, extra);
                    break;
                case WidgetRender.T_MATH:
                    handleMath(ctx, snap, state, act, extra);
                    break;
                case WidgetRender.T_MAJP:
                    handleMajorPoints(ctx, state, act, extra);
                    break;
                case WidgetRender.T_MAJQ:
                    handleMajorQuiz(ctx, snap, state, act, extra);
                    break;
            }

            Store.writeState(ctx, state.toString());
            if (snapStr != null) Store.writeSnapshot(ctx, snap.toString());
            WidgetRender.refreshOne(ctx, type);
        } catch (Exception e) {
            Log.e(TAG, "onReceive " + type + "/" + act, e);
        }
    }

    // ---------- queue ----------
    static void queue(Context ctx, JSONObject a) {
        try {
            JSONArray arr = new JSONArray(Store.readActions(ctx));
            arr.put(a);
            Store.writeActions(ctx, arr.toString());
        } catch (JSONException e) {
            Log.w(TAG, "queue", e);
        }
    }

    static JSONObject ex(String s) {
        if (s == null) return new JSONObject();
        try { return new JSONObject(s); } catch (JSONException e) { return new JSONObject(); }
    }

    // ---------- 1. plan ----------
    static void handlePlan(Context ctx, JSONObject snap, JSONObject state, String act, String extra) {
        JSONObject e = ex(extra);
        String pid = e.optString("pid", "");
        if ("toggle".equals(act)) {
            JSONArray plan = snap.optJSONArray("plan");
            if (plan != null) {
                for (int i = 0; i < plan.length(); i++) {
                    JSONObject it = plan.optJSONObject(i);
                    if (it != null && pid.equals(it.optString("id", ""))) {
                        boolean done = !it.optBoolean("done", false);
                        try { it.put("done", done); } catch (JSONException ignore) {}
                        // re-sort: undone first
                        try {
                            JSONArray sorted = new JSONArray();
                            for (int k = 0; k < plan.length(); k++) {
                                JSONObject x = plan.optJSONObject(k);
                                if (x != null && !x.optBoolean("done", false)) sorted.put(x);
                            }
                            for (int k = 0; k < plan.length(); k++) {
                                JSONObject x = plan.optJSONObject(k);
                                if (x != null && x.optBoolean("done", false)) sorted.put(x);
                            }
                            snap.remove("plan");
                            snap.put("plan", sorted);
                        } catch (JSONException ignore) {}
                        break;
                    }
                }
            }
            JSONObject a = new JSONObject();
            try { a.put("t", "planToggle"); a.put("id", pid); } catch (JSONException ignore) {}
            queue(ctx, a);
        }
    }

    // ---------- 2. life ----------
    static void handleLife(Context ctx, JSONObject snap, JSONObject state, String act, String extra) {
        JSONObject e = ex(extra);
        JSONObject life = snap.has("life") ? snap.optJSONObject("life") : new JSONObject();
        try {
            if ("meal".equals(act)) {
                JSONObject meals = life.has("meals") ? life.optJSONObject("meals") : new JSONObject();
                meals.put(e.optString("key", ""), e.optBoolean("val", false));
                life.put("meals", meals);
                JSONObject a = new JSONObject();
                a.put("t", "lifeMeal"); a.put("key", e.optString("key", "")); a.put("val", e.optBoolean("val", false));
                queue(ctx, a);
            } else if ("water".equals(act)) {
                int v = e.optInt("val", 0);
                life.put("water", v);
                JSONObject a = new JSONObject();
                a.put("t", "lifeWater"); a.put("val", v);
                queue(ctx, a);
            } else if ("bowel".equals(act)) {
                int v = e.optInt("val", 0);
                life.put("bowel", v != 0);
                JSONObject a = new JSONObject();
                a.put("t", "lifeBowel"); a.put("val", v != 0);
                queue(ctx, a);
            }
            snap.put("life", life);
        } catch (JSONException ignore) {}
    }

    // ---------- 3. words ----------
    static void handleWords(Context ctx, JSONObject state, String act, String extra) {
        try {
            JSONObject snap = WidgetRender.parse(Store.readSnapshot(ctx));
            JSONObject words = snap.has("words") ? snap.optJSONObject("words") : null;
            JSONArray p = (words != null) ? words.optJSONArray("pool") : null;
            int n = (p != null) ? p.length() : 1;
            if ("nav".equals(act)) {
                int d = ex(extra).optInt("d", 1);
                int idx = (state.optInt("wordIdx", 0) + d) % n;
                if (idx < 0) idx += n;
                state.put("wordIdx", idx);
                state.put("wordsRevealed", false);
                state.put("wordLearned", false);
            } else if ("reveal".equals(act)) {
                state.put("wordsRevealed", !state.optBoolean("wordsRevealed", false));
            } else if ("learned".equals(act)) {
                int idx = ex(extra).optInt("idx", -1);
                JSONObject a = new JSONObject();
                a.put("t", "wordLearned"); a.put("idx", idx);
                queue(ctx, a);
                state.put("wordLearned", true);
            }
        } catch (JSONException ignore) {}
    }

    // ---------- 4. spell ----------
    static void handleSpell(Context ctx, JSONObject state, String act, String extra) {
        try {
            if ("key".equals(act)) {
                String ch = state.optString("spellTyped", "") + ex(extra).optString("ch", "");
                if (ch.length() > 30) ch = ch.substring(0, 30);
                state.put("spellTyped", ch);
                state.remove("spellRes");
            } else if ("del".equals(act)) {
                String ch = state.optString("spellTyped", "");
                if (ch.length() > 0) ch = ch.substring(0, ch.length() - 1);
                state.put("spellTyped", ch);
                state.remove("spellRes");
            } else if ("submit".equals(act)) {
                JSONObject snap = WidgetRender.parse(Store.readSnapshot(ctx));
                JSONArray pool = snap.has("spellPool") ? snap.optJSONArray("spellPool") : null;
                int idx = Math.max(0, Math.min(state.optInt("spellIdx", 0), (pool != null ? pool.length() : 1) - 1));
                String answer = pool != null ? pool.optJSONObject(idx).optString("answer", "").trim().toLowerCase() : "";
                String typed = state.optString("spellTyped", "").trim().toLowerCase();
                state.put("spellRes", typed.equals(answer) ? "✅ 正确！" : ("❌ 正确拼写：" + answer));
            } else if ("next".equals(act)) {
                JSONObject snap = WidgetRender.parse(Store.readSnapshot(ctx));
                JSONArray pool = snap.has("spellPool") ? snap.optJSONArray("spellPool") : null;
                int n = (pool != null) ? pool.length() : 1;
                int idx = (state.optInt("spellIdx", 0) + 1) % n;
                state.put("spellIdx", idx);
                state.put("spellTyped", "");
                state.remove("spellRes");
            }
        } catch (JSONException ignore) {}
    }

    // ---------- 5. math ----------
    static void handleMath(Context ctx, JSONObject snap, JSONObject state, String act, String extra) {
        try {
            if ("pick".equals(act)) {
                // 组件里点 A/B/C/D：立刻在桌面标出对错，答错同时入 App 错题本
                int sel = ex(extra).optInt("sel", -1);
                JSONArray m = snap.optJSONArray("math");
                int n = (m != null && m.length() > 0) ? m.length() : 0;
                if (n == 0) return;
                int qi = Math.max(0, Math.min(state.optInt("mathQi", 0), n - 1));
                JSONObject q = m.optJSONObject(qi);
                int correct = MathListWidgetService.correctOf(q);
                state.put("mathAnswered", true);
                state.put("mathSel", sel);
                if (q != null && (correct < 0 || sel != correct)) {
                    JSONObject a = new JSONObject();
                    a.put("t", "mathWrong");
                    a.put("q", q);
                    queue(ctx, a);
                }
            } else if ("reveal".equals(act)) {
                state.put("mathRevealed", !state.optBoolean("mathRevealed", false));
            } else if ("next".equals(act)) {
                JSONArray m = snap.has("math") ? snap.optJSONArray("math") : null;
                int n = (m != null && m.length() > 0) ? m.length() : 1;
                int idx = (state.optInt("mathQi", 0) + 1) % n;
                state.put("mathQi", idx);
                state.put("mathRevealed", false);
                state.put("mathAnswered", false);
                state.put("mathSel", -1);
            } else if ("ok".equals(act) || "wrong".equals(act)) {
                JSONObject q = ex(extra);
                JSONObject a = new JSONObject();
                a.put("t", "ok".equals(act) ? "mathOk" : "mathWrong");
                a.put("q", q);
                queue(ctx, a);
            }
        } catch (JSONException ignore) {}
    }

    // ---------- 6. major points ----------
    static void handleMajorPoints(Context ctx, JSONObject state, String act, String extra) {
        try {
            if ("next".equals(act)) {
                JSONObject snap = WidgetRender.parse(Store.readSnapshot(ctx));
                JSONObject mp = snap.has("majorPoints") ? snap.optJSONObject("majorPoints") : null;
                JSONArray items = mp != null ? mp.optJSONArray("items") : null;
                int n = (items != null) ? items.length() : 1;
                int idx = (state.optInt("majPi", 0) + 1) % n;
                state.put("majPi", idx);
            } else if ("fav".equals(act)) {
                JSONObject a = new JSONObject();
                a.put("t", "majFavPoint"); a.put("id", ex(extra).optString("id", ""));
                queue(ctx, a);
            }
        } catch (JSONException ignore) {}
    }

    // ---------- 7. major quiz ----------
    static void handleMajorQuiz(Context ctx, JSONObject snap, JSONObject state, String act, String extra) {
        try {
            if ("answer".equals(act)) {
                int sel = ex(extra).optInt("sel", -1);
                JSONArray qs = snap.has("majorQuiz") ? snap.optJSONObject("majorQuiz").optJSONArray("questions") : null;
                int qi = Math.max(0, Math.min(state.optInt("majQi", 0), (qs != null ? qs.length() : 1) - 1));
                JSONObject q = qs != null ? qs.optJSONObject(qi) : null;
                int ans = q != null ? q.optInt("answer", 0) : -1;
                state.put("majAnswered", true);
                state.put("majSel", sel);
                if (sel != ans) {
                    JSONObject a = new JSONObject();
                    a.put("t", "majWrong");
                    if (q != null) a.put("q", q);
                    queue(ctx, a);
                }
            } else if ("fav".equals(act)) {
                JSONObject e = ex(extra);
                JSONObject a = new JSONObject();
                a.put("t", "majFav"); a.put("type", e.optString("type", "")); a.put("id", e.optString("id", ""));
                queue(ctx, a);
            } else if ("next".equals(act)) {
                JSONArray qs = snap.has("majorQuiz") ? snap.optJSONObject("majorQuiz").optJSONArray("questions") : null;
                int n = (qs != null) ? qs.length() : 1;
                int idx = (state.optInt("majQi", 0) + 1) % n;
                state.put("majQi", idx);
                state.put("majAnswered", false);
                state.put("majSel", -1);
            }
        } catch (JSONException ignore) {}
    }
}
