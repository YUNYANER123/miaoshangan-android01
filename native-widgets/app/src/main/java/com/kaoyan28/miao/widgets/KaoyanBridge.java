package com.kaoyan28.miao.widgets;

import com.kaoyan28.miao.R;

import android.content.Intent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.PluginMethod;

import org.json.JSONObject;

/**
 * Bridge between the web app (JS) and native widgets.
 *   pushSnapshot(value) : 桌面组件视图快照
 *   pullActions()       : 组件点击动作队列
 *   getSnapshot()       : 调试用
 *   getStore/putStore   : 兼容入口（整串，旧版用）
 *   storePutStart/Part/End、storeGetMeta/Part : v1.53r⑩ 主存档分片镜像
 *
 * v1.53r⑩：前几版都是「原生主动来找 WebView」(addJavascriptInterface / 插件被动等待 /
 * evaluateJavascript 轮询)，三次都因 bridge 时序或拿不到 WebView 而空跑。这一版由页面
 * 在 save() 时主动调用本插件，每片 <= 12000 字符，写入 getFilesDir()/kaoyan_store.json。
 * 大字符串一次性跨 bridge 会被截断/返回 null，分片是这条通道唯一可靠的用法。
 */
@CapacitorPlugin(name = "KaoyanBridge")
public class KaoyanBridge extends Plugin {

    private static final int STORE_CHUNK = 12000;
    private static String[] putParts = null;
    private static long putTs = 0;
    private static String cachedData = "";

    /* ---------- v1.53r⑩：分片写入（JS 主动调用，三个方法必须按 Start→Part*→End 顺序） ---------- */
    @PluginMethod()
    public void storePutStart(PluginCall call) {
        JSObject r = new JSObject();
        try {
            long ts = Long.parseLong(call.getString("ts", "0"));
            int n = Integer.parseInt(call.getString("n", "0"));
            if (n <= 0 || n > 400) throw new IllegalArgumentException("bad n");
            synchronized (StoreKeeper.class) {
                putTs = ts;
                putParts = new String[n];
            }
            r.put("ok", true);
        } catch (Exception e) { r.put("ok", false); }
        call.resolve(r);
    }

    @PluginMethod()
    public void storePutPart(PluginCall call) {
        JSObject r = new JSObject();
        try {
            int i = Integer.parseInt(call.getString("i", "-1"));
            String d = call.getString("data", "");
            synchronized (StoreKeeper.class) {
                if (putParts == null || i < 0 || i >= putParts.length) throw new IllegalStateException("no buffer");
                putParts[i] = d;
            }
            r.put("ok", true);
        } catch (Exception e) { r.put("ok", false); }
        call.resolve(r);
    }

    @PluginMethod()
    public void storePutEnd(PluginCall call) {
        JSObject r = new JSObject();
        try {
            long ts = Long.parseLong(call.getString("ts", "0"));
            int len = Integer.parseInt(call.getString("len", "0"));
            String data;
            synchronized (StoreKeeper.class) {
                if (putParts == null || putTs != ts) throw new IllegalStateException("no buffer");
                StringBuilder sb = new StringBuilder();
                for (String p : putParts) {
                    if (p == null) throw new IllegalStateException("missing part");
                    sb.append(p);
                }
                data = sb.toString();
                putParts = null;
            }
            // 长度/首尾/JSONObject 三重校验：任一片丢失都绝不写坏存档
            boolean intact = data.length() == len && data.startsWith("{") && data.endsWith("}");
            String raw = intact ? StoreKeeper.wrap(ts, data) : "";
            boolean ok = intact && !raw.isEmpty() && StoreKeeper.writeRaw(getContext(), raw);
            r.put("ok", ok);
            r.put("len", data.length());
        } catch (Exception e) { r.put("ok", false); r.put("len", 0); }
        call.resolve(r);
    }

    /* ---------- v1.53r⑩：冷启动分片读回 ---------- */
    @PluginMethod()
    public void storeGetMeta(PluginCall call) {
        JSObject r = new JSObject();
        try {
            JSONObject o = new JSONObject(StoreKeeper.readRaw(getContext()));
            String data = o.optString("data", "");
            long ts = o.optLong("ts", 0);
            synchronized (StoreKeeper.class) { cachedData = data; }
            r.put("ts", ts);
            r.put("len", data.length());
            r.put("n", (data.length() + STORE_CHUNK - 1) / STORE_CHUNK);
        } catch (Exception e) {
            r.put("ts", 0); r.put("len", 0); r.put("n", 0);
        }
        call.resolve(r);
    }

    @PluginMethod()
    public void storeGetPart(PluginCall call) {
        JSObject r = new JSObject();
        try {
            int i = Integer.parseInt(call.getString("i", "-1"));
            String d;
            synchronized (StoreKeeper.class) { d = cachedData; }
            int from = i * STORE_CHUNK;
            if (i < 0 || from >= d.length()) throw new IndexOutOfBoundsException("bad part");
            r.put("data", d.substring(from, Math.min(d.length(), from + STORE_CHUNK)));
            r.put("ok", true);
        } catch (Exception e) { r.put("data", ""); r.put("ok", false); }
        call.resolve(r);
    }

    /* ---------- 兼容入口 ---------- */
    @PluginMethod()
    public void getStore(PluginCall call) {
        JSObject r = new JSObject();
        r.put("value", StoreKeeper.readRaw(getContext()));
        call.resolve(r);
    }

    @PluginMethod()
    public void putStore(PluginCall call) {
        String v = call.getString("value", null);
        JSObject r = new JSObject();
        r.put("ok", v != null && StoreKeeper.writeRaw(getContext(), v));
        call.resolve(r);
    }

    @PluginMethod()
    public void pushSnapshot(PluginCall call) {
        String v = call.getString("value", null);
        if (v != null) Store.writeSnapshot(getContext(), v);
        String st = call.getString("store", null);
        if (st != null) StoreKeeper.writeRaw(getContext(), st);
        Intent i = new Intent(WidgetActionReceiver.ACTION_REFRESH);
        i.setPackage(getContext().getPackageName());
        getContext().sendBroadcast(i);
        JSObject r = new JSObject();
        r.put("ok", true);
        call.resolve(r);
    }

    @PluginMethod()
    public void pullActions(PluginCall call) {
        String a = Store.readActions(getContext());
        Store.writeActions(getContext(), "[]");
        JSObject r = new JSObject();
        r.put("actions", a == null ? "[]" : a);
        call.resolve(r);
    }

    @PluginMethod()
    public void getSnapshot(PluginCall call) {
        String s = Store.readSnapshot(getContext());
        JSObject r = new JSObject();
        r.put("snapshot", s == null ? "" : s);
        call.resolve(r);
    }
}
