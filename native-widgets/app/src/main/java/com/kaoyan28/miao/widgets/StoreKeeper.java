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
 * 主存档的原生持久化（v1.53r⑨）。
 *
 * r⑥/r⑦/r⑧ 连续三次失效，看用户录屏后找到共同点：备份 JSON 约 480 KB，
 * 而三版都在把整份 store 一次性跨 JS↔原生通道传（插件参数 / evaluateJavascript 返回值）。
 * Android WebView 的 bridge 对单次大字符串会截断或返回 null，于是「写入」每次都静默失败，
 * 原生文件从来没真正生成；r⑧ 还依赖 getBridge().getWebView() 取 WebView，也可能一直没拿到。
 *
 * r⑨ 两条硬约束：
 *   1) 所有跨 bridge 的字符串都 <= CHUNK(12000 字符)，整份数据分片串行传输；
 *   2) WebView 由 MainActivity 遍历 View 树取得，不碰任何 Capacitor API。
 * 另外轮询从 2s 缩到 250ms：录屏里「导入成功 → 点打卡 → 清后台」只有约 1.4 秒，
 * 2 秒轮询根本来不及写第一份存档。
 */
public class StoreKeeper {
    public static final String FILE = "kaoyan_store.json";
    private static final String KEY = "kaoyan28_v1";
    private static final String TS_KEY = "kaoyan28_v1_ts";
    private static final String CLEARED = "kaoyan28_v1_cleared";
    private static final String PART = "__sk_p";
    private static final int CHUNK = 12000;
    private static final long POLL_MS = 250;

    private final Context ctx;
    private final WebView wv;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean restored = false;
    private boolean busy = false;
    private long lastSeenTs = -1;
    private long fileTs = 0;
    private String startupRaw = "";

    public StoreKeeper(Context c, WebView v) {
        ctx = c.getApplicationContext();
        wv = v;
    }

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
            // renameTo 在目标已存在时部分 Android 文件系统会失败 → 先删再改名，失败从 .bak 回滚
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

    /** evaluateJavascript 返回的是 JSON 编码的字符串，需反转义成原始文本。 */
    private static String unquote(String v) {
        if (v == null || v.equals("null")) return "";
        try { return new JSONArray("[" + v + "]").getString(0); }
        catch (Exception e) { return v.replaceAll("^\"|\"$", ""); }
    }

    /* ---------- 生命周期 ---------- */
    public void start() {
        startupRaw = readRaw(ctx);
        fileTs = tsOf(startupRaw);
        handler.postDelayed(ticker, 300);
    }

    public void flush() { if (!busy) backupNow(); }

    private final Runnable ticker = new Runnable() {
        public void run() {
            try {
                if (!restored) tryRestore();
                if (!busy) backupNow();
            } catch (Exception ignore) { }
            handler.postDelayed(this, POLL_MS);
        }
    };

    /* ---------- 恢复：原生文件分片注入 localStorage ---------- */
    private void tryRestore() {
        if (startupRaw.isEmpty()) { restored = true; return; }
        wv.evaluateJavascript(
            "(function(){try{" +
            "if(document.readyState!=='complete'||!document.getElementById('app'))return 'WAIT';" +
            "var c='',cur='',ct=0;" +
            "try{c=localStorage.getItem('" + CLEARED + "')||'';" +
            "cur=localStorage.getItem('" + KEY + "')||'';" +
            "ct=+(localStorage.getItem('" + TS_KEY + "')||0)||0}catch(e){}" +
            "return JSON.stringify({s:c==='1'?'CLEARED':'READY',n:cur.length,t:ct})}catch(e){return 'WAIT'}})()",
            new ValueCallback<String>() {
                public void onReceiveValue(String r) {
                    String o = unquote(r);
                    if (o == null || !o.startsWith("{")) return;
                    try {
                        JSONObject j = new JSONObject(o);
                        restored = true;
                        // 用户主动「清空所有数据」：不要用旧存档顶回来
                        if ("CLEARED".equals(j.optString("s"))) return;
                        JSONObject file = new JSONObject(startupRaw);
                        long nt = file.optLong("ts", 0);
                        String data = file.optString("data", "");
                        long ct = j.optLong("t", 0);
                        int localLen = j.optInt("n", 0);
                        if (!(localLen == 0 || nt > ct || (nt > 0 && data.length() > localLen + 500))) return;
                        injectChunked(data, nt);
                    } catch (Exception ignore) { }
                }
            });
    }

    private void injectChunked(final String data, final long ts) {
        final int n = (data.length() + CHUNK - 1) / CHUNK;
        final int[] i = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = new Runnable() {
            public void run() {
                if (i[0] >= n) {
                    wv.evaluateJavascript(
                        "(function(){try{var s='';" +
                        "for(var k=0;k<" + n + ";k++){s+=localStorage.getItem('" + PART + "'+k)||'';" +
                        "localStorage.removeItem('" + PART + "'+k)}" +
                        "localStorage.setItem('" + KEY + "',s);" +
                        "localStorage.setItem('" + TS_KEY + "',String(" + ts + "));" +
                        "if(typeof window.__kaoyanRestore==='function'){window.__kaoyanRestore(s);}" +
                        "return 'ok'}catch(e){return 'err'}})()", null);
                    return;
                }
                final int idx = i[0]++;
                String part = data.substring(idx * CHUNK, Math.min(data.length(), (idx + 1) * CHUNK));
                wv.evaluateJavascript(
                    "(function(){try{localStorage.setItem('" + PART + idx + "'," + JSONObject.quote(part) + ");return 'ok'}catch(e){return 'err'}})()",
                    new ValueCallback<String>() {
                        public void onReceiveValue(String v) { handler.post(step[0]); }
                    });
            }
        };
        handler.post(step[0]);
    }

    /* ---------- 备份：先读小状态，再分片读完整数据 ---------- */
    private void backupNow() {
        if (busy) return;
        wv.evaluateJavascript(
            "(function(){try{return JSON.stringify({t:+(localStorage.getItem('" + TS_KEY + "')||0)||0," +
            "c:localStorage.getItem('" + CLEARED + "')||''," +
            "n:(localStorage.getItem('" + KEY + "')||'').length})}catch(e){return '0'}})()",
            new ValueCallback<String>() {
                public void onReceiveValue(String r) {
                    String o = unquote(r);
                    if (o == null || !o.startsWith("{")) return;
                    try {
                        JSONObject j = new JSONObject(o);
                        long t = j.optLong("t", 0);
                        final boolean cleared = "1".equals(j.optString("c", ""));
                        final int len = j.optInt("n", 0);
                        if (t <= 0 || t == lastSeenTs || t <= fileTs || len == 0) return;
                        busy = true;
                        readChunks(t, len, cleared);
                    } catch (Exception ignore) { }
                }
            });
    }

    private void readChunks(final long t, final int len, final boolean cleared) {
        final int n = (len + CHUNK - 1) / CHUNK;
        final StringBuilder sb = new StringBuilder();
        final int[] i = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = new Runnable() {
            public void run() {
                if (i[0] >= n) { finishBackup(t, sb.toString(), cleared); return; }
                final int idx = i[0]++;
                wv.evaluateJavascript(
                    "(function(){try{return (localStorage.getItem('" + KEY + "')||'').slice(" +
                    (idx * CHUNK) + "," + ((idx + 1) * CHUNK) + ")}catch(e){return ''}})()",
                    new ValueCallback<String>() {
                        public void onReceiveValue(String v) {
                            sb.append(unquote(v));
                            handler.post(step[0]);
                        }
                    });
            }
        };
        handler.post(step[0]);
    }

    private void finishBackup(long t, String data, boolean cleared) {
        try {
            // 任一分片丢失都会让 JSON 不完整 → valid() 拒绝，绝不写坏文件
            if (data.startsWith("{") && data.endsWith("}")) {
                String raw = wrap(t, data);
                if (!raw.isEmpty() && writeRaw(ctx, raw)) {
                    fileTs = t;
                    lastSeenTs = t;
                    if (cleared) {
                        wv.evaluateJavascript(
                            "(function(){try{localStorage.removeItem('" + CLEARED + "')}catch(e){}})()", null);
                    }
                }
            }
        } catch (Exception ignore) { }
        finally { busy = false; }
    }
}
