package com.kaoyan28.miao.widgets;

import android.content.Context;
import android.webkit.JavascriptInterface;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * v1.53r⑥：原生文件存储桥。
 * WebView 的 localStorage 在安卓上不可靠（即便默认 capacitor://localhost 源，重启也可能丢），
 * 因此把主存档额外镜像到一个应用私有文件（getFilesDir()/kaoyan_store.json），
 * 该文件随 App 数据目录可靠持久，重启不丢。
 * 通过 addJavascriptInterface 暴露为 window.NativeStore，方法同步返回（JS 侧可直接读）。
 */
public class NativeStoreBridge {
    private final Context ctx;
    private static final String FILE = "kaoyan_store.json";

    public NativeStoreBridge(Context ctx) { this.ctx = ctx; }

    @JavascriptInterface
    public String getStore() {
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists()) return "";
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return "";
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    @JavascriptInterface
    public void putStore(String json) {
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            FileOutputStream out = new FileOutputStream(f);
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception e) { /* ignore */ }
    }
}
