package com.kaoyan28.miao.widgets;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 主存档的原生持久化（v1.53r⑧）。
 *
 * 不再假设「JS 能主动调到原生」：
 *   - r⑥ 的 addJavascriptInterface 注册在 super.onCreate() 之后，Capacitor 那时已 loadUrl，
 *     页面里 window.NativeStore 恒为 undefined；
 *   - r⑦ 改走 KaoyanBridge 插件 getStore/putStore，但插件通道是否可用依然无法保证，
 *     用户实测重进 App 数据仍被初始化。
 * 这里改为由原生侧主动用 WebView.evaluateJavascript 直接读写 localStorage：
 *   - 每 2 秒（以及 onPause/onStop）把 kaoyan28_v1 + 时间戳备份到
 *     getFilesDir()/kaoyan_store.json，原子写 + .bak 兜底；
 *   - 冷启动时若原生文件比 localStorage 新（或 localStorage 为空 / 明显被重置），
 *     把数据注入回 localStorage，并调用 window.__kaoyanRestore() 让页面重绘。
 * 只要 WebView 能执行 JS，存档就一定在；不依赖插件注册、注入对象时序或 JS 主动调用。
 * WebView 每轮都会重新尝试获取，避免 Bridge 尚未就绪时永久跳过。
 */
public class StoreKeeper {
    public static final String FILE = "kaoyan_store.json";
    private static final String KEY = "kaoyan28_v1";
    private static final String TS_KEY = "kaoyan28_v1_ts";
    private static final String CLEARED = "kaoyan28_v1_cleared";
    private static final long POLL_MS = 2000;

    private final Context ctx;
    private final WebView wv;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean restored = false;
    private long lastSeenTs = -1;
    private long fileTs = 0;
    private String startupRaw = "";

    /** WebView 由 MainActivity（BridgeActivity 子类）在就绪后传入，避免跨包调用 getBridge()。 */
    public StoreKeeper(Context c, WebView v) {
        ctx = c.getApplicationContext();
        wv = v;
    }

    private WebView webView() { return wv; }

    /* ---------- 文件层：插件通道也复用这里，静态 synchronized 避免并发写互相截断 ---------- */
    private static File mainFile(Context c) { return new File(c.getFilesDir(), FILE); }

    private static String readAll(File file) {
        try {
            if (!file.exists() || file.length() == 0) return "";
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return new String(bo.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) { return ""; }
    }

    private static boolean valid(String s) {
        if (s == null || !s.startsWith("{") || !s.endsWith("}")) return false;
        try { new JSONObject(s); return true; } catch (Exception e) { return false; }
    }

    public static synchronized String readRaw(Context c) {
        String s = readAll(mainFile(c));
        if (valid(s)) return s;
        // 主文件被写坏/不存在 → 回退上一份备份，绝不因为一次半截写入丢全部数据
        String bak = readAll(new File(c.getFilesDir(), FILE + ".bak"));
        return valid(bak) ? bak : "";
    }

    public static synchronized boolean writeRaw(Context c, String raw) {
        if (!valid(raw)) return false;
        try {
            File d = c.getFilesDir();
            File main = new File(d, FILE);
            File tmp = new File(d, FILE + ".tmp");
            File bak = new File(d, FILE + ".bak");
            String prev = readAll(main);
            if (valid(prev)) {
                FileOutputStream bo = new FileOutputStream(bak);
                bo.write(prev.getBytes(StandardCharsets.UTF_8));
                bo.flush();
                bo.close();
            }
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
            out.close();
            // File.renameTo 在「目标文件已存在」时部分 Android 文件系统会直接失败，
            // 导致主文件永远停在第一次写入的内容 → 先删除目标再改名；失败则从 .bak 回滚。
            if (main.exists() && !main.delete()) { /* 继续尝试 rename */ }
            if (!tmp.renameTo(main)) {
                if (valid(prev)) {
                    FileOutputStream ro = new FileOutputStream(main);
                    ro.write(prev.getBytes(StandardCharsets.UTF_8));
                    ro.flush();
                    ro.close();
                }
                return false;
            }
            return true;
        } catch (Exception e) { return false; }
    }

    public static long tsOf(String raw) {
        try { return new JSONObject(raw).optLong("ts", 0); } catch (Exception e) { return 0; }
    }

    public static String wrap(long ts, String data) {
        try {
            JSONObject o = new JSONObject();
            o.put("ts", ts);
            o.put("data", data);
            return o.toString();
        } catch (Exception e) { return ""; }
    }

    /* ---------- WebView 层 ---------- */
    /** evaluateJavascript 返回的是 JSON 编码的字符串，需反转义成原始文本。 */
    private static String unquote(String v) {
        if (v == null || v.equals("null")) return "";
        try { return new JSONArray("[" + v + "]").getString(0); }
        catch (Exception e) { return v.replaceAll("^\"|\"$", ""); }
    }

    public void start() {
        startupRaw = readRaw(ctx);
        fileTs = tsOf(startupRaw);
        handler.postDelayed(ticker, 600);
    }

    /** Activity.onPause / onStop 时立刻再备份一次，避免轮询间隔内被杀进程。 */
    public void flush() { backupNow(); }

    private final Runnable ticker = new Runnable() {
        public void run() {
            try {
                if (!restored) tryRestore();
                backupNow();
            } catch (Exception ignore) { }
            handler.postDelayed(this, POLL_MS);
        }
    };

    /** 等到页面真正就绪（app.js 已执行完 load()）再注入，避免在 about:blank 上白注入。 */
    private void tryRestore() {
        if (startupRaw.isEmpty()) { restored = true; return; }
        final WebView v = webView();
        if (v == null) return;
        v.evaluateJavascript(
            "(function(){try{" +
            "if(document.readyState!=='complete'||!document.getElementById('app'))return 'WAIT';" +
            "var c='';try{c=localStorage.getItem('" + CLEARED + "')||''}catch(e){}" +
            "return c==='1'?'CLEARED':'READY'}catch(e){return 'WAIT'}})()",
            new ValueCallback<String>() {
                public void onReceiveValue(String r) {
                    if (r == null || (!r.contains("READY") && !r.contains("CLEARED"))) return;
                    restored = true;
                    // 用户在设置页主动「清空所有数据」：不要用旧存档覆盖这次清空。
                    // backupNow() 会把新的空存档写进文件并清掉标记。
                    if (r.contains("CLEARED")) return;
                    String quoted = JSONObject.quote(startupRaw);
                    String js =
                        "(function(){try{" +
                        "var o=JSON.parse(" + quoted + ");" +
                        "var cur='',ct=0;" +
                        "try{cur=localStorage.getItem('" + KEY + "')||'';" +
                        "ct=+(localStorage.getItem('" + TS_KEY + "')||0)||0}catch(e){}" +
                        "var nt=+(o.ts||0)||0;" +
                        "var d=String(o.data||'');" +
                        // 三种情况都恢复：本地被清空 / 原生文件更新 / 本地被重置成 defaults
                        // （defaults 的 JSON 远小于含计划、单词进度、备忘录的真实存档）
                        "if(!cur||nt>ct||(nt>0&&d.length>cur.length+500)){" +
                        "  localStorage.setItem('" + KEY + "',d);" +
                        "  localStorage.setItem('" + TS_KEY + "',String(nt));" +
                        "  if(typeof window.__kaoyanRestore==='function'){window.__kaoyanRestore(d);}" +
                        "  return 'RESTORED';}" +
                        "return 'LOCAL_NEWER';}catch(e){return 'ERR:'+e.message}})()";
                    v.evaluateJavascript(js, null);
                }
            });
    }

    /** 先只读一个很小的时间戳，变了才读整份数据，避免每 2 秒回传大字符串。 */
    private void backupNow() {
        final WebView v = webView();
        if (v == null) return;
        v.evaluateJavascript(
            "(function(){try{return String(+(localStorage.getItem('" + TS_KEY + "')||0)||0)}catch(e){return '0'}})()",
            new ValueCallback<String>() {
                public void onReceiveValue(String r) {
                    long t;
                    try { t = Long.parseLong(unquote(r).trim()); } catch (Exception e) { return; }
                    if (t <= 0 || t == lastSeenTs || t <= fileTs) return;
                    lastSeenTs = t;
                    readAndWrite(t);
                }
            });
    }

    private void readAndWrite(final long t) {
        final WebView v = webView();
        if (v == null) return;
        v.evaluateJavascript(
            "(function(){try{return JSON.stringify({d:localStorage.getItem('" + KEY + "')||''," +
            "c:localStorage.getItem('" + CLEARED + "')||''})}catch(e){return ''}})()",
            new ValueCallback<String>() {
                public void onReceiveValue(String r) {
                    String obj = unquote(r);
                    if (obj == null || !obj.startsWith("{")) return;
                    String data = "";
                    boolean cleared = false;
                    try {
                        JSONObject o = new JSONObject(obj);
                        data = o.optString("d", "");
                        cleared = "1".equals(o.optString("c", ""));
                    } catch (Exception e) { return; }
                    if (!data.startsWith("{") || !data.endsWith("}")) return;
                    String raw = wrap(t, data);
                    if (!raw.isEmpty() && writeRaw(ctx, raw)) {
                        fileTs = t;
                        if (cleared) {
                            v.evaluateJavascript(
                                "(function(){try{localStorage.removeItem('" + CLEARED + "')}catch(e){}})()", null);
                        }
                    }
                }
            });
    }
}
