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
 *                          and refreshes all home-screen widgets immediately.
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
        // 每次推送快照都顺手排程「午夜翻页」闹钟：保证只要 App 当天打开/保存过，
        // 即使之后不再打开，跨 00:00 组件也能自动按设备当前日期重绘到新一天。
        try { WidgetRender.scheduleMidnight(getContext()); } catch (Exception ignore) {}
        // 关键修复：Android 8+ 会静默丢弃「manifest 上注册的隐式广播」——
        // 旧实现用 sendBroadcast(ACTION_REFRESH)（只 setPackage、未 setClass）属于隐式广播，
        // 在绝大多数机型上根本到不了 WidgetActionReceiver，于是「保存 / 导入数据后桌面组件不刷新，
        // 必须重新添加才显示」（背单词/拼写没有导入数据也一直空白、专业课知识点/题目导入后仍空白）。
        // 改为同进程内直接刷新所有已添加的组件：任何数据变化（导入、勾选、标星、导航）都立即生效。
        try { WidgetRender.refreshAll(getContext()); } catch (Exception ignore) {}
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

    // Capacitor 自带的 App 插件未在本工程注册（JS 侧 Capacitor.Plugins.App 取不到），
    // 导致设置页读不到版本号。这里直接用 PackageManager 读真实 versionName 供网页显示。
    @PluginMethod()
    public void getAppVersion(PluginCall call) {
        JSObject r = new JSObject();
        try {
            android.content.pm.PackageInfo pi = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0);
            r.put("version", pi.versionName == null ? "" : pi.versionName);
            r.put("build", String.valueOf(pi.versionCode));
        } catch (Exception e) {
            r.put("version", "");
            r.put("build", "");
        }
        call.resolve(r);
    }

    // 桌面组件点 🐱 跳对应页：原生 MainActivity 把目标页写进启动 extra 并暂存到静态变量，
    // 网页启动后调本方法取出并跳转（取出即清空，避免冷启动后再被 onResume 重复跳转）。
    @PluginMethod()
    public void getLaunchPage(PluginCall call) {
        String p = com.kaoyan28.miao.MainActivity.consumeLaunchPage();
        JSObject r = new JSObject();
        r.put("page", p == null ? "" : p);
        call.resolve(r);
    }

    // v1.53p：把网页 Canvas 生成的计划图片存进系统相册。
    // Android 10+ 走 MediaStore + RELATIVE_PATH(Pictures/MiaoShangAn)，无需任何运行时权限；
    // Android 9 及以下回退到传统 MediaStore 插入（配合 manifest 里 maxSdkVersion=28 的写外部存储权限）。
    @PluginMethod()
    public void saveImage(PluginCall call) {
        String b64 = call.getString("base64", "");
        String name = call.getString("filename", "miaoshanggan.jpg");
        if (b64 == null || b64.length() == 0) { call.reject("empty image"); return; }
        try {
            byte[] data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            android.content.ContentResolver cr = getContext().getContentResolver();
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/MiaoShangAn");
                cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            }
            android.net.Uri uri = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) { call.reject("insert failed"); return; }
            java.io.OutputStream os = cr.openOutputStream(uri);
            if (os == null) { call.reject("open stream failed"); return; }
            os.write(data);
            os.flush();
            os.close();
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                cv.clear();
                cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, cv, null, null);
            }
            JSObject r = new JSObject();
            r.put("ok", true);
            r.put("uri", uri.toString());
            call.resolve(r);
        } catch (Exception e) {
            call.reject("save failed: " + e.getMessage());
        }
    }
}
