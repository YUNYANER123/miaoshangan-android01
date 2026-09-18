package com.kaoyan28.miao.widgets;

import com.kaoyan28.miao.R;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tiny file-based store shared between the WebView (JS -> KaoyanBridge plugin)
 * and the native App Widgets. Three files live in the app's private files dir:
 *   - kaoyan_snapshot.json   : view-model pushed by the web app (single source of truth mirror)
 *   - kaoyan_actions.json    : queue of widget-originated mutations for the app to apply
 *   - kaoyan_widget_state.json: widget-local UI state (current index, reveal flags, ...)
 */
public final class Store {
    static final String SNAP = "kaoyan_snapshot.json";
    static final String ACT = "kaoyan_actions.json";
    static final String ST = "kaoyan_widget_state.json";
    private static final String TAG = "KaoyanWidget";

    private Store() {}

    static File file(Context c, String n) {
        return new File(c.getFilesDir(), n);
    }

    public static String read(Context c, String n) {
        try {
            File f = file(c, n);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0, r;
            while (off < buf.length && (r = in.read(buf, off, buf.length - off)) >= 0) off += r;
            in.close();
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "read " + n, e);
            return null;
        }
    }

    public static void write(Context c, String n, String v) {
        try {
            FileOutputStream out = new FileOutputStream(file(c, n));
            out.write(v.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception e) {
            Log.w(TAG, "write " + n, e);
        }
    }

    public static String readSnapshot(Context c) { return read(c, SNAP); }
    public static void writeSnapshot(Context c, String v) { write(c, SNAP, v); }
    public static String readActions(Context c) { return read(c, ACT); }
    public static void writeActions(Context c, String v) { write(c, ACT, v); }
    public static String readState(Context c) { return read(c, ST); }
    public static void writeState(Context c, String v) { write(c, ST, v); }
}
