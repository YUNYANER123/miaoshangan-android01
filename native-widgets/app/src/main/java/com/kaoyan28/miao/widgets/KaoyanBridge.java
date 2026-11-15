package com.kaoyan28.miao.widgets;

import com.kaoyan28.miao.R;

import android.content.Intent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.PluginMethod;

/**
 * Bridge between the web app (JS) and native widgets.
 *   pushSnapshot(value)  : web app calls on every save -> persists the view-model
 *                          and broadcasts a refresh so all home-screen widgets update.
 *   pullActions()        : web app calls on resume -> returns queued widget mutations
 *                          (as a JSON array string) and clears the queue.
 *   getSnapshot()        : returns the current snapshot (used for debugging).
 *   getStore()/putStore(): 主存档读写，底层统一委托 StoreKeeper（静态 synchronized）。
 *
 * v1.53r⑧：主存档的备份/恢复不再由本插件承担。前两版分别踩了
 *   - addJavascriptInterface 在 loadUrl 之后注册 → 页面里 window.NativeStore 恒 undefined；
 *   - 插件通道是否可用无法保证 → 用户实测重进 App 数据仍被初始化。
 * 现在由 MainActivity 里的 StoreKeeper 主动 evaluateJavascript 读写 localStorage，
 * 本插件的 getStore/putStore 只作为兼容入口保留，共用同一套原子文件写入逻辑，
 * 避免两条通道并发写坏同一个文件。
 */
@CapacitorPlugin(name = "KaoyanBridge")
public class KaoyanBridge extends Plugin {

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
        // 兼容旧版 app.js 顺带传来的 store 包装串；新版 app.js 不再传，由 StoreKeeper 独占写入。
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
