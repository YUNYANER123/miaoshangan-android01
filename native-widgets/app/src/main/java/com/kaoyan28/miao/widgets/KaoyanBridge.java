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
 */
@CapacitorPlugin(name = "KaoyanBridge")
public class KaoyanBridge extends Plugin {

    @PluginMethod()
    public void pushSnapshot(PluginCall call) {
        String v = call.getString("value", null);
        if (v != null) Store.writeSnapshot(getContext(), v);
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
