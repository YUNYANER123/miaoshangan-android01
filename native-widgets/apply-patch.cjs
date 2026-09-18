/* eslint-disable */
/**
 * apply-patch.cjs
 * ----------------
 * Runs in CI AFTER `npx cap sync android`. The Capacitor android platform is
 * regenerated from scratch on every build, so our native widget code cannot
 * live inside `android/`. Instead it lives in `native-widgets/app/...` and this
 * script injects it into the generated project:
 *   1. copy Java sources  -> android/app/src/main/java/...
 *   2. copy res (drawable/layout/values/xml) -> android/app/src/main/res/...
 *   3. register the KaoyanBridge plugin in MainActivity
 *   4. declare the 7 AppWidgetProviders + the interaction receiver in the manifest
 *
 * Idempotent: safe to run more than once.
 */
const fs = require('fs');
const path = require('path');

const ROOT = process.cwd();
const SRC = path.join(ROOT, 'native-widgets', 'app');
const ANDROID = path.join(ROOT, 'android');
const JAVA_DST = path.join(ANDROID, 'app', 'src', 'main', 'java');
const RES_DST = path.join(ANDROID, 'app', 'src', 'main', 'res');

function log(m) { console.log('[widget-patch] ' + m); }

function copyDir(src, dst, skip) {
  if (!fs.existsSync(src)) return;
  fs.mkdirSync(dst, { recursive: true });
  for (const ent of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, ent.name);
    const d = path.join(dst, ent.name);
    if (ent.isDirectory()) {
      copyDir(s, d, skip);
    } else {
      if (skip && skip(ent.name)) { log('  skip ' + ent.name); continue; }
      fs.copyFileSync(s, d);
      log('  -> ' + path.relative(ROOT, d));
    }
  }
}

// ---- 1 & 2: copy sources ----
log('copying native widget sources into android/');
copyDir(path.join(SRC, 'src', 'main', 'java'), JAVA_DST, (n) => false);
copyDir(path.join(SRC, 'src', 'main', 'res'), RES_DST, (n) => n === 'styles.xml');

// ---- 3: MainActivity ----
const mainAct = path.join(JAVA_DST, 'com', 'kaoyan28', 'miao', 'MainActivity.java');
if (fs.existsSync(mainAct)) {
  let m = fs.readFileSync(mainAct, 'utf8');
  if (!m.includes('KaoyanBridge')) {
    if (!m.includes('import android.os.Bundle;')) {
      m = m.replace(/(package com\.kaoyan28\.miao;)/, '$1\nimport android.os.Bundle;');
    }
    m = m.replace(
      /(package com\.kaoyan28\.miao;)/,
      '$1\nimport com.kaoyan28.miao.widgets.KaoyanBridge;'
    );
    if (m.includes('super.onCreate')) {
      m = m.replace(
        'super.onCreate(savedInstanceState);',
        'registerPlugin(KaoyanBridge.class);\n        super.onCreate(savedInstanceState);'
      );
    } else {
      m = m.replace(
        /(public class MainActivity extends BridgeActivity \{)/,
        '$1\n\n    @Override\n    public void onCreate(Bundle savedInstanceState) {\n        registerPlugin(KaoyanBridge.class);\n        super.onCreate(savedInstanceState);\n    }'
      );
    }
    // 强制 WebView 100% 缩放并适配宽度，避免部分机型（如 VIVO）因系统字体 / 显示大小放大
    // 导致页面被放大、右侧溢出屏幕。浏览器（Chrome）通常不继承这套 OEM 缩放，故网页版正常。
    if (!m.includes('setTextZoom(100)')) {
      m = m.replace(
        'super.onCreate(savedInstanceState);',
        'super.onCreate(savedInstanceState);\n' +
        '        try {\n' +
        '          android.webkit.WebView wv = (getBridge() != null) ? getBridge().getWebView() : null;\n' +
        '          if (wv != null) {\n' +
        '            android.webkit.WebSettings ws = wv.getSettings();\n' +
        '            ws.setTextZoom(100);\n' +
        '            ws.setUseWideViewPort(true);\n' +
        '            ws.setLoadWithOverviewMode(true);\n' +
        '            ws.setSupportZoom(false);\n' +
        '            ws.setBuiltInZoomControls(false);\n' +
        '            ws.setDisplayZoomControls(false);\n' +
        '          }\n' +
        '        } catch (Exception ignore) {}'
      );
    }
    fs.writeFileSync(mainAct, m);
    log('patched MainActivity.java (registered KaoyanBridge + WebView zoom-fit)');
  } else {
    log('MainActivity.java already patched');
  }
} else {
  log('WARN: MainActivity.java not found at ' + mainAct);
}

// ---- 4: AndroidManifest ----
const mf = path.join(ANDROID, 'app', 'src', 'main', 'AndroidManifest.xml');
if (fs.existsSync(mf)) {
  let x = fs.readFileSync(mf, 'utf8');
  if (!x.includes('WidgetActionReceiver')) {
    if (!x.includes('RECEIVE_BOOT_COMPLETED')) {
      x = x.replace(
        '<application',
        '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n\n    <application'
      );
    }
    const widgets = [
      ['PlanWidget', 'widget_plan', '今日计划'],
      ['LifeWidget', 'widget_life', '生活记录'],
      ['WordsWidget', 'widget_words', '背单词'],
      ['SpellWidget', 'widget_spell', '随机拼写'],
      ['MathWidget', 'widget_math', '数学今日题'],
      ['MajorPointsWidget', 'widget_major_points', '专业课知识点'],
      ['MajorQuizWidget', 'widget_major_quiz', '专业课题目'],
    ];
    let block = '\n        <!-- 喵上岸 桌面小组件 -->\n';
    block +=
      '        <receiver android:name="com.kaoyan28.miao.widgets.WidgetActionReceiver" android:exported="false">\n' +
      '            <intent-filter>\n' +
      '                <action android:name="com.kaoyan28.miao.ACTION_WIDGET_DO" />\n' +
      '                <action android:name="com.kaoyan28.miao.ACTION_REFRESH" />\n' +
      '                <action android:name="android.intent.action.BOOT_COMPLETED" />\n' +
      '            </intent-filter>\n' +
      '        </receiver>\n';
    for (const [cls, res, label] of widgets) {
      block +=
        '        <receiver android:name="com.kaoyan28.miao.widgets.' + cls + '" android:label="' + label + '" android:exported="true">\n' +
        '            <intent-filter>\n' +
        '                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />\n' +
        '            </intent-filter>\n' +
        '            <meta-data android:name="android.appwidget.provider" android:resource="@xml/' + res + '" />\n' +
        '        </receiver>\n';
    }
    x = x.replace('</application>', block + '    </application>');
    fs.writeFileSync(mf, x);
    log('patched AndroidManifest.xml (added widget receivers)');
  } else {
    log('AndroidManifest.xml already patched');
  }

  // ---- 4b: RemoteViewsService（ListView 型组件的取数服务）----
  let s = fs.readFileSync(mf, 'utf8');
  if (!s.includes('PlanListWidgetService')) {
    const svcBlock =
      '        <!-- 列表型组件的 RemoteViewsService（只有集合视图能滚动） -->\n' +
      '        <service android:name="com.kaoyan28.miao.widgets.PlanListWidgetService"\n' +
      '            android:permission="android.permission.BIND_REMOTEVIEWS"\n' +
      '            android:exported="true" />\n' +
      '        <service android:name="com.kaoyan28.miao.widgets.MathListWidgetService"\n' +
      '            android:permission="android.permission.BIND_REMOTEVIEWS"\n' +
      '            android:exported="true" />\n' +
      '        <service android:name="com.kaoyan28.miao.widgets.SpellListWidgetService"\n' +
      '            android:permission="android.permission.BIND_REMOTEVIEWS"\n' +
      '            android:exported="true" />\n';
    
    s = s.replace('</application>', svcBlock + '    </application>');
    fs.writeFileSync(mf, s);
    log('patched AndroidManifest.xml (added RemoteViewsService)');
  } else {
    log('AndroidManifest.xml already has RemoteViewsService');
  }
} else {
  log('WARN: AndroidManifest.xml not found at ' + mf);
}

log('done.');
