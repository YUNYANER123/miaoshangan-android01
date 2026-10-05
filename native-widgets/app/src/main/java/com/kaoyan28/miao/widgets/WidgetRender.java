package com.kaoyan28.miao.widgets;

import com.kaoyan28.miao.R;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Build;
import android.widget.RemoteViews;

import java.util.Calendar;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds the RemoteViews for every home-screen widget from the shared snapshot
 * (pushed by the web app) + the widget-local UI state. Also owns the
 * PendingIntent factory and the refresh dispatcher.
 */
public final class WidgetRender {

    // ---- widget types ----
    public static final String T_PLAN = "plan";
    public static final String T_LIFE = "life";
    public static final String T_WORDS = "words";
    public static final String T_SPELL = "spell";
    public static final String T_MATH = "math";
    public static final String T_MAJP = "majorPoints";
    public static final String T_MAJQ = "majorQuiz";

    // ---- 各组件点 🐱 后跳转到的 App 页面（与网页 index.html 的 data-page 一致）----
    public static final String PAGE_PLAN = "plan";
    public static final String PAGE_LIFE = "life";
    public static final String PAGE_ENGLISH = "english";
    public static final String PAGE_MATH = "math";
    public static final String PAGE_MAJOR = "major";

    // ---- blue / white theme ----
    static final int C_TITLE = 0xFF1E3A5F;   // navy
    static final int C_BODY = 0xFF27496B;     // slate blue
    static final int C_MUTED = 0xFF6B8299;    // muted blue-gray
    static final int C_DONE = 0xFF9AA7B5;     // gray (done)
    static final int C_ACCENT = 0xFF2F80ED;   // blue
    static final int C_WHITE = 0xFFFFFFFF;
    static final String CAT = "\uD83D\uDC31"; // 🐱

    private WidgetRender() {}

    // ============================================================
    //  Refresh dispatcher
    // ============================================================
    public static void refreshAll(Context ctx) {
        refreshOne(ctx, T_PLAN);
        refreshOne(ctx, T_LIFE);
        refreshOne(ctx, T_WORDS);
        refreshOne(ctx, T_SPELL);
        refreshOne(ctx, T_MATH);
        refreshOne(ctx, T_MAJP);
        refreshOne(ctx, T_MAJQ);
    }

    public static void refreshOne(Context ctx, String type) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        ComponentName cn = new ComponentName(ctx, providerClass(type));
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) return;
        String snap = Store.readSnapshot(ctx);
        String state = Store.readState(ctx);
        RemoteViews rv = render(ctx, type, snap, state);
        mgr.updateAppWidget(ids, rv);
        // 集合类视图（ListView）的数据由 RemoteViewsService 提供。
        // updateAppWidget 只是换了模板，必须再 notify 一次才会重新 bind / 取数，
        // 否则点一行标记完成后列表不会变。
        if (T_PLAN.equals(type)) {
            try { mgr.notifyAppWidgetViewDataChanged(ids, R.id.plan_list); } catch (Exception ignore) {}
        } else if (T_MATH.equals(type)) {
            try { mgr.notifyAppWidgetViewDataChanged(ids, R.id.m_list); } catch (Exception ignore) {}
        } else if (T_SPELL.equals(type)) {
            try { mgr.notifyAppWidgetViewDataChanged(ids, R.id.spell_list); } catch (Exception ignore) {}
        }
    }

    // ============================================================
    //  午夜翻页：即使不打开 App，跨 00:00 也让组件按设备当前日期刷新
    // ============================================================

    /** 设备当前日期，格式 YYYY-MM-DD（与网页 todayStr / 快照 date 字段一致）。 */
    public static String todayDevice() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 取某一天（设备日期）的「每日类」数据；快照缺 days 或当天不存在时回退到顶层（今天）字段。 */
    static JSONObject dayFor(JSONObject snap, String td) {
        JSONObject f = new JSONObject();
        try {
            JSONObject days = snap != null ? snap.optJSONObject("days") : null;
            JSONObject d = (days != null) ? days.optJSONObject(td) : null;
            if (d != null) return d;
            // 兜底：老版本快照 / days 缺失 —— 直接用顶层字段拼出「今天」对象
            if (snap != null) {
                if (snap.has("plan")) f.put("plan", snap.optJSONArray("plan"));
                if (snap.has("life")) f.put("life", snap.optJSONObject("life"));
                if (snap.has("spellPool")) f.put("spellPool", snap.optJSONArray("spellPool"));
                if (snap.has("math")) f.put("math", snap.optJSONArray("math"));
                if (snap.has("majorPoints")) f.put("majorPoints", snap.optJSONObject("majorPoints"));
                if (snap.has("majorQuiz")) f.put("majorQuiz", snap.optJSONObject("majorQuiz"));
            }
        } catch (JSONException ignore) {}
        return f;
    }

    /** 排程一个「下一次 00:00」的精确闹钟，到点广播 ACTION_REFRESH 让所有组件重绘。
     *  RTC_WAKEUP 保证设备休眠也能触发；每次排程都取下一个 00:00，自然实现「每天一次」。 */
    public static void scheduleMidnight(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, WidgetActionReceiver.class);
            i.setAction(WidgetActionReceiver.ACTION_REFRESH);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0x4D314E49, i, flags());
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 3);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
            else
                am.setExact(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
        } catch (Exception ignore) {}
    }

    static Class<?> providerClass(String type) {
        switch (type) {
            case T_PLAN: return PlanWidget.class;
            case T_LIFE: return LifeWidget.class;
            case T_WORDS: return WordsWidget.class;
            case T_SPELL: return SpellWidget.class;
            case T_MATH: return MathWidget.class;
            case T_MAJP: return MajorPointsWidget.class;
            case T_MAJQ: return MajorQuizWidget.class;
            default: return PlanWidget.class;
        }
    }

    // ============================================================
    //  Dispatch
    // ============================================================
    public static RemoteViews render(Context ctx, String type, String snapJson, String stateJson) {
        try {
            JSONObject snap = parse(snapJson);
            JSONObject state = parseState(stateJson);
            // 按设备当前日期取「每日类」数据（午夜翻页的关键：跨 00:00 后 todayDevice() 变成新一天）
            JSONObject day = dayFor(snap, todayDevice());
            switch (type) {
                case T_PLAN: return renderPlan(ctx, day, state);
                case T_LIFE: return renderLife(ctx, day, state);
                case T_WORDS: return renderWords(ctx, snap, state);
                case T_SPELL: return renderSpell(ctx, day, state);
                case T_MATH: return renderMath(ctx, day, state);
                case T_MAJP: return renderMajorPoints(ctx, day, state);
                case T_MAJQ: return renderMajorQuiz(ctx, day, state);
                default: return empty(ctx, type, "未知组件");
            }
        } catch (Exception e) {
            return empty(ctx, type, "小部件暂时无法显示\n点此打开 App");
        }
    }

    // ============================================================
    //  Helpers
    // ============================================================
    static int flags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    static PendingIntent pi(Context ctx, String type, String action, String extra, int salt) {
        Intent i = new Intent(ctx, WidgetActionReceiver.class);
        i.setAction(WidgetActionReceiver.ACTION_DO);
        i.putExtra("type", type);
        i.putExtra("action", action);
        if (extra != null) i.putExtra("extra", extra);
        int rc = (type.hashCode() ^ action.hashCode() ^ salt) & 0x7FFFFFFF;
        return PendingIntent.getBroadcast(ctx, rc, i, flags());
    }

    /** PendingIntent.FLAG_MUTABLE (API 31+)。用字面量是为了不依赖 compileSdk >= 31。 */
    private static final int FLAG_MUTABLE_ = 0x02000000;

    static int flagsMutable() {
        if (Build.VERSION.SDK_INT >= 31)
            return PendingIntent.FLAG_UPDATE_CURRENT | FLAG_MUTABLE_;
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    /**
     * 集合视图（ListView）专用的「点击模板」。
     *
     * 列表行不能直接 setOnClickPendingIntent，必须由 provider 端设模板、
     * 行内用 setOnClickFillInIntent 把参数补进去。Android 12+ 规定能被
     * fill-in 改写的 PendingIntent 必须是 MUTABLE，否则行内 extras 会被丢掉。
     */
    static PendingIntent piTemplate(Context ctx, String type, String action) {
        Intent i = new Intent(ctx, WidgetActionReceiver.class);
        i.setAction(WidgetActionReceiver.ACTION_DO);
        i.putExtra("type", type);
        i.putExtra("action", action);
        int rc = (type.hashCode() ^ action.hashCode() ^ 0x5EED) & 0x7FFFFFFF;
        return PendingIntent.getBroadcast(ctx, rc, i, flagsMutable());
    }

    static PendingIntent openApp(Context ctx, String page) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (i == null) i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        if (page != null && !page.isEmpty()) i.putExtra("page", page);
        // 不同页面用不同的 requestCode，避免 FLAG_UPDATE_CURRENT 把 extra 互相覆盖
        // （否则所有组件的 🐱 都会开到同一个页面）。
        int rc = 99001 + (page == null ? 0 : (Math.abs(page.hashCode()) % 997));
        return PendingIntent.getActivity(ctx, rc, i, flags());
    }

    /** 点 🐱 直接进入启动页（无特定页面）。 */
    static PendingIntent openApp(Context ctx) { return openApp(ctx, null); }

    /** 组件类型 → 点 🐱 要跳转的 App 页面。 */
    static String pageForType(String type) {
        switch (type) {
            case T_PLAN: return PAGE_PLAN;
            case T_LIFE: return PAGE_LIFE;
            case T_WORDS: return PAGE_ENGLISH;
            case T_SPELL: return PAGE_ENGLISH;
            case T_MATH: return PAGE_MATH;
            case T_MAJP: return PAGE_MAJOR;
            case T_MAJQ: return PAGE_MAJOR;
            default: return PAGE_PLAN;
        }
    }

    static JSONObject parse(String s) {
        if (s == null || s.isEmpty()) return new JSONObject();
        try { return new JSONObject(s); } catch (JSONException e) { return new JSONObject(); }
    }

    static JSONObject parseState(String s) {
        if (s == null || s.isEmpty()) return defaultState();
        try { return new JSONObject(s); } catch (JSONException e) { return defaultState(); }
    }

    /** 判断整数 v 是否在 JSON 数组 a 中（用于 words.favs）。 */
    static boolean arrHas(JSONArray a, int v) {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) { if (a.optInt(i, -1) == v) return true; }
        return false;
    }

    static JSONObject defaultState() {
        JSONObject o = new JSONObject();
        try {
            o.put("wordIdx", 0); o.put("wordsRevealed", false); o.put("wordLearned", false);
            o.put("spellIdx", 0); o.put("spellTyped", "");
            o.put("mathQi", 0); o.put("mathRevealed", false);
            o.put("mathAnswered", false); o.put("mathSel", -1);
            o.put("majPi", 0); o.put("majQi", 0); o.put("majAnswered", false);
        } catch (JSONException ignore) {}
        return o;
    }

    static void setHeader(RemoteViews rv, String title) {
        rv.setTextViewText(R.id.w_cat, CAT);
        rv.setTextViewText(R.id.w_title, title);
        // 头部日期一律显示设备当前日期，确保跨 00:00 自动翻到新一天（不再依赖快照里冻结的 date 字段）
        rv.setTextViewText(R.id.w_date, todayDevice());
        rv.setTextColor(R.id.w_title, C_TITLE);
        rv.setTextColor(R.id.w_date, C_MUTED);
    }

    /** 每个组件自己的布局（空态也要用对的布局，否则会显示成别的组件）。 */
    static int layoutFor(String type) {
        switch (type) {
            case T_LIFE: return R.layout.widget_life;
            case T_WORDS: return R.layout.widget_words;
            case T_SPELL: return R.layout.widget_spell;
            case T_MATH: return R.layout.widget_math;
            case T_MAJP: return R.layout.widget_major_points;
            case T_MAJQ: return R.layout.widget_major_quiz;
            default: return R.layout.widget_plan;
        }
    }

    /** 每个组件的标题（标题栏左上角，不能再用 App 名「喵上岸」）。 */
    static String titleFor(String type) {
        switch (type) {
            case T_LIFE: return "生活记录";
            case T_WORDS: return "背单词";
            case T_SPELL: return "随机拼写";
            case T_MATH: return "数学今日题";
            case T_MAJP: return "专业课知识点";
            case T_MAJQ: return "专业课题目";
            default: return "今日计划";
        }
    }

    /**
     * 空态 / 出错兜底：用「该组件自己的布局 + 自己的标题」渲染，并保证整块可点
     * （点标题 / 点猫 / 点提示文字都会打开 App —— App 启动后会推快照并刷新所有组件）。
     */
    static RemoteViews empty(Context ctx, String type, String msg) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), layoutFor(type));
        setHeader(rv, titleFor(type));
        rv.setViewVisibility(R.id.w_body, android.view.View.GONE);
        rv.setViewVisibility(R.id.w_empty, android.view.View.VISIBLE);
        rv.setTextViewText(R.id.w_empty, msg == null ? "" : msg);
        rv.setTextColor(R.id.w_empty, C_MUTED);
        rv.setOnClickPendingIntent(R.id.w_cat, openApp(ctx, pageForType(type)));
        rv.setOnClickPendingIntent(R.id.w_empty, openApp(ctx, pageForType(type)));
        try { rv.setOnClickPendingIntent(R.id.w_title, openApp(ctx, pageForType(type))); } catch (Exception ignore) {}
        return rv;
    }

    static void strike(RemoteViews rv, int id, boolean on) {
        int f = Paint.STRIKE_THRU_TEXT_FLAG | Paint.ANTI_ALIAS_FLAG;
        rv.setInt(id, "setPaintFlags", on ? f : Paint.ANTI_ALIAS_FLAG);
    }

    // ============================================================
    //  1. 今日计划
    // ============================================================
    static RemoteViews renderPlan(Context ctx, JSONObject day, JSONObject state) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_plan);
        setHeader(rv, "今日计划");
        JSONArray plan = day != null ? day.optJSONArray("plan") : null;
        if (plan == null || plan.length() == 0) {
            return empty(ctx, T_PLAN, "还没有今日计划\n点 🐱 进 App 添加并同步");
        }
        rv.setViewVisibility(R.id.w_empty, android.view.View.GONE);
        rv.setViewVisibility(R.id.w_body, android.view.View.VISIBLE);
        // 标题显示「今日已完成 / 今日总数」，一眼看到进度
        int pTotal = plan.length();
        int pDone = 0;
        for (int i = 0; i < pTotal; i++) {
            JSONObject it = plan.optJSONObject(i);
            if (it != null && it.optBoolean("done", false)) pDone++;
        }
        rv.setTextViewText(R.id.w_title, "今日计划 " + pDone + "/" + pTotal);
        // 列表行由 PlanListWidgetService 逐行提供（只有集合视图才能滚动，ScrollView 不在白名单里）
        Intent svc = new Intent(ctx, PlanListWidgetService.class);
        rv.setRemoteAdapter(R.id.plan_list, svc);
        rv.setPendingIntentTemplate(R.id.plan_list, piTemplate(ctx, T_PLAN, "toggle"));
        rv.setTextViewText(R.id.w_tip, "点 🐱 进 App · 点一行标记完成（列表可滑动）");
        rv.setTextColor(R.id.w_tip, C_MUTED);
        rv.setOnClickPendingIntent(R.id.w_cat, openApp(ctx, PAGE_PLAN));
        return rv;
    }

    // ============================================================
    //  2. 生活记录
    // ============================================================
    static RemoteViews renderLife(Context ctx, JSONObject day, JSONObject state) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_life);
        setHeader(rv, "生活记录");
        JSONObject life = day != null ? day.optJSONObject("life") : null;
        JSONObject meals = life != null ? life.optJSONObject("meals") : null;
        if (meals == null) meals = new JSONObject();
        int water = life != null ? life.optInt("water", 0) : 0;
        boolean bowel = life != null && life.optBoolean("bowel", false);

        String[][] toggles = {
            {"bf", "🍳 早餐", "meal_bf"}, {"lunch", "🍱 午餐", "meal_lunch"},
            {"dinner", "🍲 晚餐", "meal_dinner"}, {"exercise", "🏃 运动", "meal_exercise"}
        };
        for (String[] t : toggles) {
            int id = ctx.getResources().getIdentifier(t[2], "id", ctx.getPackageName());
            boolean on = meals.optBoolean(t[0], false);
            rv.setTextViewText(id, t[1] + (on ? " ✓" : ""));
            rv.setTextColor(id, on ? C_WHITE : C_BODY);
            rv.setInt(id, "setBackgroundColor", on ? C_ACCENT : 0xFFD6E6FF);
            JSONObject ex = new JSONObject();
            try { ex.put("key", t[0]); ex.put("val", !on); } catch (JSONException ignore) {}
            rv.setOnClickPendingIntent(id, pi(ctx, T_LIFE, "meal", ex.toString(), t[0].hashCode()));
        }
        rv.setTextViewText(R.id.water_val, "💧 " + water + " 杯");
        rv.setTextColor(R.id.water_val, C_BODY);
        JSONObject exM = new JSONObject();
        try { exM.put("val", Math.max(0, water - 1)); } catch (JSONException ignore) {}
        JSONObject exP = new JSONObject();
        try { exP.put("val", Math.min(8, water + 1)); } catch (JSONException ignore) {}
        rv.setOnClickPendingIntent(R.id.water_minus, pi(ctx, T_LIFE, "water", exM.toString(), 1));
        rv.setOnClickPendingIntent(R.id.water_plus, pi(ctx, T_LIFE, "water", exP.toString(), 2));
        rv.setTextViewText(R.id.bowel, "💩 " + (bowel ? "已记录" : "未记录"));
        rv.setTextColor(R.id.bowel, bowel ? C_WHITE : C_BODY);
        rv.setInt(R.id.bowel, "setBackgroundColor", bowel ? C_ACCENT : 0xFFD6E6FF);
        JSONObject exB = new JSONObject();
        try { exB.put("val", bowel ? 0 : 1); } catch (JSONException ignore) {}
        rv.setOnClickPendingIntent(R.id.bowel, pi(ctx, T_LIFE, "bowel", exB.toString(), 3));
        rv.setTextViewText(R.id.w_tip, "点 🐱 进 App 记录");
        rv.setTextColor(R.id.w_tip, C_MUTED);
        rv.setOnClickPendingIntent(R.id.w_cat, openApp(ctx, PAGE_LIFE));
        return rv;
    }

    // ============================================================
    //  3. 背单词
    // ============================================================
    static RemoteViews renderWords(Context ctx, JSONObject snap, JSONObject state) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_words);
        setHeader(rv, "背单词");
        JSONObject words = snap != null ? snap.optJSONObject("words") : null;
        JSONArray pool = words != null ? words.optJSONArray("pool") : null;
        if (pool == null || pool.length() == 0) {
            return empty(ctx, T_WORDS, "暂无单词数据\n点此打开 App 同步");
        }
        // 以 App 当前单词（快照 words.idx）为权威：当 App 端的当前单词下标发生变化时，
        // 组件收敛回 pool[0]（= App 当前词），并重置「显示释义 / 已标记已背」状态。
        // 这样从 App 切回组件永远看到的是 App 当前词，且默认隐藏释义、未标记已背，
        // 不会因为 pool 随 App 推进而平移、把旧词的 reveal/learned 误套到新词上。
        int snapIdx = words.optInt("idx", 0);
        if (state != null && state.optInt("lastSnapIdx", -1) != snapIdx) {
            try {
                state.put("wordIdx", 0);
                state.put("wordsRevealed", false);
                state.put("wordLearned", false);
                state.put("lastSnapIdx", snapIdx);
                Store.writeState(ctx, state.toString());
            } catch (JSONException ignore) {}
        }
        int idx = Math.max(0, Math.min(state.optInt("wordIdx", 0), pool.length() - 1));
        JSONObject w = pool.optJSONObject(idx);
        if (w == null) w = pool.optJSONObject(0);
        boolean revealed = state.optBoolean("wordsRevealed", false);
        // 已计入状态以快照为准：w.learned = 该词在 App 内 store.words.learned 中（已由网页带入快照），
        // 不再用组件本地态 wordLearned（组件本地态会在每次导航被重置，导致「已背过的词」仍显示「标记已背」）。
        boolean learned = w != null && w.optBoolean("learned", false);

        // 标星按钮：App 的 store.words.favs 为权威来源；widget 本地 state.wordFaved 仅作即时反馈，
        // 若两者冲突以快照为准并自愈（避免 App 侧取消标星后 widget 仍显示星）。
        JSONArray wfavs = words != null ? words.optJSONArray("favs") : null;
        int gidx = w != null ? w.optInt("idx", idx) : idx;
        boolean snapFav = arrHas(wfavs, gidx);
        // 本地乐观标记按「单词下标」记录：只有被点的那个词才显示★，
        // 避免给 A 标星后翻到 B，B 也跟着显示★（星标串位）。
        boolean stFav = state != null && state.optInt("wordFavedIdx", -1) == gidx;
        // 自愈：仅当快照已确认该词被标星（App 已写入 store.words.favs）时才清掉 widget 本地的
        // 乐观标记，避免与快照重复。绝不能因为「快照还没更新」就清掉 wordFaved —— 否则刚点下的
        // 星标会被立刻还原成☆，表现为「点了没反应 / 点不动」（这就是之前标星按钮失效的原因）。
        if (snapFav && stFav) {
            if (state != null) state.remove("wordFavedIdx");
            stFav = false;
            try { Store.writeState(ctx, state.toString()); } catch (Exception ignore) {}
        }
        boolean faved = snapFav || stFav;
        rv.setTextViewText(R.id.w_fav, faved ? "\u2605" : "\u2606");
        rv.setTextColor(R.id.w_fav, faved ? 0xFFF5B301 : 0xFF9AA7B5);

        rv.setTextViewText(R.id.w_word, w.optString("word", ""));
        rv.setTextViewText(R.id.w_phone, w.optString("phonetic", ""));
        rv.setTextViewText(R.id.w_mean, revealed ? w.optString("meaning", "") : "🔒 点「显示释义」");
        rv.setTextColor(R.id.w_word, C_TITLE);
        rv.setTextColor(R.id.w_phone, C_MUTED);
        rv.setTextColor(R.id.w_mean, revealed ? C_BODY : C_MUTED);

        int today = words.optInt("todayCnt", 0);
        int total = words.optInt("totalLearned", 0);
        int goal = words.optInt("goal", 0);
        String stat = "今日已背 " + today + " · 累计 " + total + (goal > 0 ? " · 目标 " + goal : "");
        rv.setTextViewText(R.id.w_stat, stat);
        rv.setTextColor(R.id.w_stat, C_MUTED);

        rv.setOnClickPendingIntent(R.id.w_prev, pi(ctx, T_WORDS, "nav", "{\"d\":-1}", 1));
        rv.setOnClickPendingIntent(R.id.w_next, pi(ctx, T_WORDS, "nav", "{\"d\":1}", 2));
        rv.setOnClickPendingIntent(R.id.w_reveal, pi(ctx, T_WORDS, "reveal", null, 3));
        JSONObject exL = new JSONObject();
        try { exL.put("idx", w.optInt("idx", idx)); } catch (JSONException ignore) {}
        rv.setOnClickPendingIntent(R.id.w_learned, pi(ctx, T_WORDS, "learned", exL.toString(), 4));
        JSONObject fex = new JSONObject();
        try { fex.put("idx", gidx); } catch (JSONException ignore) {}
        rv.setOnClickPendingIntent(R.id.w_fav, pi(ctx, T_WORDS, "fav", fex.toString(), 5));
        rv.setTextViewText(R.id.w_learned, learned ? "已计入 ✓" : "标记已背");
        rv.setTextViewText(R.id.w_tip, "点 🐱 进 App · 点释义看中文");
        rv.setTextColor(R.id.w_tip, C_MUTED);
        rv.setOnClickPendingIntent(R.id.w_cat, openApp(ctx, PAGE_ENGLISH));
        return rv;
    }

    // ============================================================
    //  4. 随机拼写
    // ============================================================
    static RemoteViews renderSpell(Context ctx, JSONObject day, JSONObject state) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_spell);
        setHeader(rv, "随机拼写");
        JSONArray pool = day != null ? day.optJSONArray("spellPool") : null;
        if (pool == null || pool.length() == 0) {
            return empty(ctx, T_SPELL, "暂无单词数据\n点此打开 App 同步");
        }
        // 输入框（已拼写）与 结果 固定在主布局里常显；只有释义放进可滑动 ListView。
        String typed = state != null ? state.optString("spellTyped", "") : "";
        rv.setTextViewText(R.id.s_display, typed.isEmpty() ? "（在此拼写）" : typed);
        rv.setTextColor(R.id.s_display, typed.isEmpty() ? C_MUTED : C_TITLE);
        String res = state != null ? state.optString("spellRes", "") : "";
        if (!res.isEmpty()) {
            rv.setViewVisibility(R.id.s_res, android.view.View.VISIBLE);
            rv.setTextViewText(R.id.s_res, res);
            rv.setTextColor(R.id.s_res, res.startsWith("\u2705") ? 0xFF2E9E5B : C_ACCENT);
        } else {
            rv.setViewVisibility(R.id.s_res, android.view.View.GONE);
        }

        // 释义走 ListView（集合视图才能在桌面组件里上下滑动，长释义不再顶没按钮）
        Intent svc = new Intent(ctx, SpellListWidgetService.class);
        rv.setRemoteAdapter(R.id.spell_list, svc);

        String alphabet = "abcdefghijklmnopqrstuvwxyz";
        for (int k = 0; k < alphabet.length(); k++) {
            char c = alphabet.charAt(k);
            int id = ctx.getResources().getIdentifier("key_" + c, "id", ctx.getPackageName());
            if (id == 0) continue;
            JSONObject ex = new JSONObject();
            try { ex.put("ch", String.valueOf(c)); } catch (JSONException ignore) {}
            rv.setOnClickPendingIntent(id, pi(ctx, T_SPELL, "key", ex.toString(), c));
        }
        rv.setOnClickPendingIntent(R.id.s_del, pi(ctx, T_SPELL, "del", null, 1));
        rv.setOnClickPendingIntent(R.id.s_submit, pi(ctx, T_SPELL, "submit", null, 2));
        rv.setOnClickPendingIntent(R.id.s_next, pi(ctx, T_SPELL, "next", null, 3));

        rv.setTextViewText(R.id.w_tip, "点 🐱 进 App 背单词 · 释义可上下滑动");
        rv.setTextColor(R.id.w_tip, C_MUTED);
        rv.setOnClickPendingIntent(R.id.w_cat, openApp(ctx, PAGE_ENGLISH));
        return rv;
    }

    // ============================================================
    //  5. 数学今日题
    // ============================================================
    static RemoteViews renderMath(Context ctx, JSONObject day, JSONObject state) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_math);
        setHeader(rv, "数学今日题");
        JSONArray math = day != null ? day.optJSONArray("math") : null;
        if (math == null || math.length() == 0) {
            return empty(ctx, T_MATH, "暂无数学题数据\n点 🐱 进 App 同步");
        }
        rv.setViewVisibility(R.id.w_empty, android.view.View.GONE);
        rv.setViewVisibility(R.id.w_body, android.view.View.VISIBLE);
        // 桌面组件放不了 EditText，所以只保留选择题：
        // 题干 + A/B/C/D 四个选项按钮（+ 答完后的解析）由 MathListWidgetService 逐行渲染，
        // 走 ListView 是为了题干/解析再长也能滚动，不会被裁掉。
        Intent svc = new Intent(ctx, MathListWidgetService.class);
        rv.setRemoteAdapter(R.id.m_list, svc);
        rv.setPendingIntentTemplate(R.id.m_list, piTemplate(ctx, T_MATH, "row"));
        rv.setTextViewText(R.id.w_tip, "点 🐱 进 App · 点 A/B/C/D 作答（列表可滑动）");
        rv.setTextColor(R.id.w_tip, C_MUTED);
        rv.setOnClickPendingIntent(R.id.w_cat, openApp(ctx, PAGE_MATH));
        return rv;
    }

    // ============================================================
    //  6. 专业课知识点
    // ============================================================
    static RemoteViews renderMajorPoints(Context ctx, JSONObject day, JSONObject state) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_major_points);
        setHeader(rv, "专业课知识点");
        JSONObject mp = day != null ? day.optJSONObject("majorPoints") : null;
        JSONArray items = mp != null ? mp.optJSONArray("items") : null;
        int count = mp != null ? mp.optInt("count", 0) : 0;
        if (items == null || items.length() == 0) {
            return empty(ctx, T_MAJP, "暂无专业课知识点\n点此打开 App 同步");
        }
        int pi = Math.max(0, Math.min(state.optInt("majPi", 0), items.length() - 1));
        JSONObject it = items.optJSONObject(pi);
        if (it == null) it = items.optJSONObject(0);
        rv.setTextViewText(R.id.mp_counter, (pi + 1) + " / " + count);
        rv.setTextViewText(R.id.mp_book, it.optString("book", ""));
        rv.setTextViewText(R.id.mp_t, it.optString("t", ""));
        rv.setTextViewText(R.id.mp_c, it.optString("c", ""));
        rv.setTextColor(R.id.mp_counter, C_ACCENT);
        rv.setTextColor(R.id.mp_book, C_MUTED);
        rv.setTextColor(R.id.mp_t, C_TITLE);
        rv.setTextColor(R.id.mp_c, C_BODY);
        // 收藏状态：快照 items[].fav 由 App 权威写入，原生乐观翻转即时反馈；文案改「⭐收藏 / √已收藏」更易辨识
        boolean mpFav = it.optBoolean("fav", false);
        rv.setTextViewText(R.id.mp_fav, mpFav ? "√已收藏" : "⭐收藏");
        rv.setTextColor(R.id.mp_fav, mpFav ? 0xFF2E9E5B : C_MUTED);
        rv.setOnClickPendingIntent(R.id.mp_next, pi(ctx, T_MAJP, "next", null, 1));
        JSONObject ex = new JSONObject();
        try { ex.put("id", it.optString("id", "")); } catch (JSONException ignore) {}
        rv.setOnClickPendingIntent(R.id.mp_fav, pi(ctx, T_MAJP, "fav", ex.toString(), 2));
        rv.setTextViewText(R.id.w_tip, "点 🐱 进 App · 点 ★ 同步收藏");
        rv.setTextColor(R.id.w_tip, C_MUTED);
        rv.setOnClickPendingIntent(R.id.w_cat, openApp(ctx, PAGE_MAJOR));
        return rv;
    }

    // ============================================================
    //  7. 专业课题目
    // ============================================================
    static RemoteViews renderMajorQuiz(Context ctx, JSONObject day, JSONObject state) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_major_quiz);
        setHeader(rv, "专业课题目");
        JSONObject mq = day != null ? day.optJSONObject("majorQuiz") : null;
        JSONArray qs = mq != null ? mq.optJSONArray("questions") : null;
        int total = qs != null ? qs.length() : 0;
        if (qs == null || total == 0) {
            return empty(ctx, T_MAJQ, "暂无专业课题目\n点此打开 App 同步");
        }
        int qi = Math.max(0, Math.min(state.optInt("majQi", 0), total - 1));
        JSONObject q = qs.optJSONObject(qi);
        if (q == null) q = qs.optJSONObject(0);
        String type = q.optString("type", "choice");
        boolean answered = state.optBoolean("majAnswered", false);

        rv.setTextViewText(R.id.mq_counter, (qi + 1) + " / " + total);
        rv.setTextViewText(R.id.mq_q, q.optString("q", ""));
        rv.setTextColor(R.id.mq_counter, C_ACCENT);
        rv.setTextColor(R.id.mq_q, C_BODY);
        // 收藏状态：快照 questions[].fav 由 App 权威写入，原生乐观翻转即时反馈；文案改「⭐收藏 / √已收藏」更易辨识
        boolean mqFav = q.optBoolean("fav", false);
        rv.setTextViewText(R.id.mq_fav, mqFav ? "√已收藏" : "⭐收藏");
        rv.setTextColor(R.id.mq_fav, mqFav ? 0xFF2E9E5B : C_MUTED);

        if ("choice".equals(type)) {
            JSONArray opts = q.optJSONArray("options");
            int n = opts != null ? opts.length() : 0;
            for (int j = 0; j < 4; j++) {
                int id = ctx.getResources().getIdentifier("mq_o" + j, "id", ctx.getPackageName());
                if (id == 0) continue;
                rv.setViewVisibility(id, android.view.View.VISIBLE);
                String label = (j < n) ? (("ABCD".charAt(j)) + ". " + opts.optString(j, "")) : "";
                rv.setTextViewText(id, label);
                rv.setTextColor(id, C_BODY);
                rv.setInt(id, "setBackgroundColor", 0xFFD6E6FF);
                JSONObject ex = new JSONObject();
                try { ex.put("sel", j); } catch (JSONException ignore) {}
                rv.setOnClickPendingIntent(id, pi(ctx, T_MAJQ, "answer", ex.toString(), 10 + j));
            }
            rv.setViewVisibility(R.id.mq_j0, android.view.View.GONE);
            rv.setViewVisibility(R.id.mq_j1, android.view.View.GONE);
        } else {
            for (int j = 0; j < 4; j++) {
                int id = ctx.getResources().getIdentifier("mq_o" + j, "id", ctx.getPackageName());
                if (id != 0) rv.setViewVisibility(id, android.view.View.GONE);
            }
            rv.setViewVisibility(R.id.mq_j0, android.view.View.VISIBLE);
            rv.setViewVisibility(R.id.mq_j1, android.view.View.VISIBLE);
            rv.setTextViewText(R.id.mq_j0, "✓ 正确");
            rv.setTextViewText(R.id.mq_j1, "✗ 错误");
            rv.setOnClickPendingIntent(R.id.mq_j0, pi(ctx, T_MAJQ, "answer", "{\"sel\":0}", 20));
            rv.setOnClickPendingIntent(R.id.mq_j1, pi(ctx, T_MAJQ, "answer", "{\"sel\":1}", 21));
        }

        if (answered) {
            int ans = q.optInt("answer", 0);
            JSONArray ansOpts = q.optJSONArray("options");
            String correct;
            if ("choice".equals(type)) {
                String optTxt = (ansOpts != null) ? ansOpts.optString(ans, "") : "";
                correct = (ans >= 0 && ans < 4 ? String.valueOf("ABCD".charAt(ans)) : "?") + ". " + optTxt;
            } else {
                correct = (ans == 0 ? "正确" : "错误");
            }
            int sel = state.optInt("majSel", -1);
            boolean right = (sel == ans);
            rv.setViewVisibility(R.id.mq_ans, android.view.View.VISIBLE);
            rv.setTextViewText(R.id.mq_ans, (right ? "✅ 答对！" : "❌ 答错。") + "正确答案：" + correct);
            rv.setTextColor(R.id.mq_ans, right ? 0xFF2E9E5B : C_ACCENT);
        } else {
            rv.setViewVisibility(R.id.mq_ans, android.view.View.GONE);
        }

        JSONObject exF = new JSONObject();
        try { exF.put("type", type); exF.put("id", q.optString("id", "")); } catch (JSONException ignore) {}
        rv.setOnClickPendingIntent(R.id.mq_fav, pi(ctx, T_MAJQ, "fav", exF.toString(), 30));
        rv.setOnClickPendingIntent(R.id.mq_next, pi(ctx, T_MAJQ, "next", null, 31));
        rv.setTextViewText(R.id.mq_prev, "上一题");
        rv.setTextColor(R.id.mq_prev, C_MUTED);
        rv.setOnClickPendingIntent(R.id.mq_prev, pi(ctx, T_MAJQ, "prev", null, 33));
        rv.setTextViewText(R.id.w_tip, "点 🐱 进 App · 点 ⭐ 同步收藏");
        rv.setTextColor(R.id.w_tip, C_MUTED);
        rv.setOnClickPendingIntent(R.id.w_cat, openApp(ctx, PAGE_MAJOR));
        return rv;
    }
}
