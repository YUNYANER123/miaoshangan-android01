package com.kaoyan28.miao.widgets;

import com.kaoyan28.miao.R;

import android.content.Intent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.PluginMethod;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bridge between the web app (JS) and native widgets.
 *   pushSnapshot(value)  : web app calls on every save -> persists the view-model
 *                          and broadcasts a refresh so all home-screen widgets update.
 *   pullActions()        : web app calls on resume -> returns queued widget mutations
 *                          (as a JSON array string) and clears the queue.
 *   getSnapshot()        : returns the current snapshot (used for debugging).
 *   getStore()/putStore(): v1.53r⑦ 主存档的原生文件镜像。
 *                          WebView 的 localStorage 在部分安卓机型上冷启动会丢，
 *                          且 addJavascriptInterface 必须在 loadUrl 之前注册才可见
 *                          （Capacitor 的 BridgeActivity 在 super.onCreate 里就 loadUrl，
 *                          因此后注册的 window.NativeStore 页面里根本不存在）。
 *                          这里改用已 registerPlugin 的 Capacitor 插件通道，100% 可用，
 *                          文件写在 getFilesDir()/kaoyan_store.json，随 App 数据持久。
 */
@CapacitorPlugin(name = "KaoyanBridge")
public class KaoyanBridge extends Plugin {

    private static final String STORE_FILE = "kaoyan_store.json";

    private String readOne(File f) {
        try {
            if (!f.exists() || f.length() == 0) return "";
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            in.close();
            String s = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8).trim() : "";
            // 完整存档一定是 JSON 对象；半截写入（进程被杀）会不以 } 结尾 → 视为损坏
            return (s.startsWith("{") && s.endsWith("}")) ? s : "";
        } catch (Exception e) { return ""; }
    }

    private String readStoreFile() {
        String s = readOne(new File(getContext().getFilesDir(), STORE_FILE));
        if (!s.isEmpty()) return s;
        // 主文件损坏/不存在 → 回退到上一份备份，绝不因为一次写坏就丢全部数据
        return readOne(new File(getContext().getFilesDir(), STORE_FILE + ".bak"));
    }

    private void writeStoreFile(String json) {
        if (json == null) return;
        try {
            File dir = getContext().getFilesDir();
            File f = new File(dir, STORE_FILE);
            File tmp = new File(dir, STORE_FILE + ".tmp");
            File bak = new File(dir, STORE_FILE + ".bak");
            // 先把当前有效存档留一份备份（只保留上一版），再原子替换主文件
            String prev = readOne(f);
            if (!prev.isEmpty()) {
                FileOutputStream bo = new FileOutputStream(bak);
                bo.write(prev.getBytes(StandardCharsets.UTF_8));
                bo.close();
            }
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
            out.close();
            if (!tmp.renameTo(f)) { /* 极少数文件系统不支持 rename，忽略即可，.bak 仍在 */ }
        } catch (Exception e) { /* ignore */ }
    }

    @PluginMethod()
    public void getStore(PluginCall call) {
        JSObject r = new JSObject();
        r.put("value", readStoreFile());
        call.resolve(r);
    }

    @PluginMethod()
    public void putStore(PluginCall call) {
        String v = call.getString("value", null);
        if (v != null) writeStoreFile(v);
        JSObject r = new JSObject();
        r.put("ok", v != null);
        call.resolve(r);
    }

    @PluginMethod()
    public void pushSnapshot(PluginCall call) {
        String v = call.getString("value", null);
        if (v != null) Store.writeSnapshot(getContext(), v);
        // v1.53r⑦：每次 save 都顺手把主存档镜像进原生文件（双保险，即使 putStore 未调用也不丢）
        String st = call.getString("store", null);
        if (st != null) writeStoreFile(st);
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
