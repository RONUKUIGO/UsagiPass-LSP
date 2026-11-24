package me.yun.usagipass;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * UsagiPass (LSP) · 作用域 com.tencent.mm。
 *
 * 只做一件事：在微信把舞萌二维码页 / 微信 OAuth 回调页交给 WebView（或装进 Intent）之前，
 * 把地址改写成 UsagiPass 前端（规则见 UsagiRewriter，逐行对齐其 server/plugins/mitmproxy.ts）。
 *
 * 不产生任何代理/VPN/路由改动，对内核级代理模块零影响。
 *
 * v0.2.0 变更：微信的 H5 容器自己的 {@code com.tencent.mm.ui.widget.MMWebView} 覆写了
 * {@code loadUrl(String)}，不会走 {@code com.tencent.xweb.WebView} 的实现，所以只 hook 基类抓不到。
 * 现在按"类 × 所有 loadUrl 重载"逐个 hook，并额外在 Activity 的 Intent 上兜一层。
 */
public class ModuleMain extends XposedModule {

    public static final String TAG = "UsagiPass";
    public static final String VERSION = "0.7.1";
    static final String TARGET_PKG = "com.tencent.mm";
    static final String PREF_NAME = "usagipass";
    /** 开发版才有设置界面与日志开关；发行版行为写死（改写常开、详细日志常关） */
    static final boolean DEV = BuildConfig.DEBUG;

    /** 微信里所有承载 H5 的 WebView 实现（静态分析 com.tencent.xweb.WebView 的 19 个子类得出） */
    private static final String[] WEBVIEW_CLASSES = {
            "android.webkit.WebView",
            "com.tencent.xweb.WebView",
            "com.tencent.xweb.pinus.sdk.WebView",
            "com.tencent.mm.ui.widget.MMWebView",
            "com.tencent.mm.plugin.webview.ui.tools.widget.MMWebViewWithJsApi",
            "com.tencent.mm.plugin.webview.ui.tools.widget.MailMMWebView",
            "com.tencent.mm.plugin.box.webview.BoxWebView",
            "com.tencent.mm.plugin.websearch.webview.BaseWebSearchWebView",
            "com.tencent.mm.plugin.websearch.webview.WebSearchWebView",
            "com.tencent.mm.plugin.appbrand.openmaterial.ui.hybrid.OpenMaterialWebView",
            "com.tencent.mm.plugin.product.ui.HtmlTextView2",
            "com.tencent.mm.plugin.qqmail.ui.EmbedWebView",
            "com.tencent.mm.plugin.webview.ui.tools.fts.s5",
    };

    private SharedPreferences prefs;
    private volatile Context appCtx;
    private final Set<String> seen = new HashSet<>();
    private final Set<String> hookedClasses = new HashSet<>();
    private final Set<Integer> bridged = new HashSet<>();   // 已注入 JS 桥的 WebView（identityHashCode）

    /** 页面里注入的脚本：**双击**二维码 → 复制 SGWC+maid（即 SGWCMAID…）。
     *  用双击而不是长按，避免和微信自己的长按图片菜单抢手势。 */
    private static final String QR_JS = """
            (function(){
              if (window.__usagiQrCopy) return true;
              window.__usagiQrCopy = 1;
              function payload(){
                try {
                  var m = new URLSearchParams(location.search).get('maid') || '';
                  return m ? ('SGWC' + m) : '';
                } catch (e) { return ''; }
              }
              function fire(){
                var p = payload();
                if (!p) return;
                try { if (window.UsagiPassBridge) { window.UsagiPassBridge.copy(p); return; } } catch (e) {}
                try { console.log('USAGI_COPY:' + p); } catch (e) {}
              }
              function tgt(t){
                if (!t) return false;
                var n = (t.tagName || '').toUpperCase();
                return n === 'IMG' || n === 'CANVAS';
              }
              document.addEventListener('dblclick', function(e){
                if (!tgt(e.target)) return;
                e.preventDefault();
                try { e.stopPropagation(); } catch (x) {}
                fire();
              }, true);
              var last = 0, lx = 0, ly = 0;
              document.addEventListener('touchend', function(e){
                if (!tgt(e.target)) return;
                var now = Date.now();
                var t = (e.changedTouches && e.changedTouches[0]) || null;
                var x = t ? t.clientX : 0, y = t ? t.clientY : 0;
                if (now - last <= 380 && Math.abs(x - lx) < 60 && Math.abs(y - ly) < 60) {
                  last = 0;
                  try { e.preventDefault(); } catch (x2) {}
                  fire();
                } else {
                  last = now; lx = x; ly = y;
                }
              }, true);
              function noZoom(){
                var ns = document.querySelectorAll('img, canvas');
                for (var i = 0; i < ns.length; i++) {
                  try { ns[i].style.touchAction = 'manipulation'; } catch (e) {}
                }
              }
              noZoom();
              setTimeout(noZoom, 1200);
              setTimeout(noZoom, 3000);
              return true;
            })();
            """;
    private volatile boolean installed = false;

    // ------------------------------------------------------------------ 入口

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded v" + VERSION + " proc=" + param.getProcessName()
                + " api=" + getApiVersion() + " fw=" + getFrameworkName() + "/" + getFrameworkVersion());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) return;
        if (installed) return;
        installed = true;
        ClassLoader cl = param.getClassLoader();
        log(Log.INFO, TAG, "package ready: " + param.getPackageName()
                + " dev=" + DEV
                + " enable=" + prefBool("enable", true) + " verbose=" + prefBool("verbose", false));
        installHooks(cl);
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        log(Log.INFO, TAG, "hot reloading");
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        log(Log.INFO, TAG, "hot reloaded v" + VERSION);
    }

    // ------------------------------------------------------------------ hook 安装

    private void installHooks(ClassLoader cl) {
        int n = 0;
        for (String cn : WEBVIEW_CLASSES) {
            n += hookAllLoadUrl(cl, cn);
            n += hookOverrideString(cl, cn + "$WebViewClientCandidate");
        }
        n += hookOverrideStringFor(cl, "android.webkit.WebViewClient");
        n += hookOverrideStringFor(cl, "com.tencent.xweb.WebViewClient");
        n += hookOverrideRequest(cl, "android.webkit.WebViewClient");
        n += hookPinusClient(cl);
        n += hookWebViewClientSetter(cl);
        n += hookActivityIntent(cl);
        log(Log.INFO, TAG, "hooks armed: " + n);
    }

    /** 把某个类里所有「名字是 loadUrl 且第 1 个参数是 String」的重载都挂上 */
    private int hookAllLoadUrl(ClassLoader cl, String className) {
        Class<?> c = loadClass(className, cl);
        if (c == null) return 0;
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"loadUrl".equals(m.getName())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 0 || !ps[0].equals(String.class)) continue;
            n += hookArg0(m, className + "#loadUrl/" + ps.length);
        }
        return n;
    }

    /** shouldOverrideUrlLoading(WebView, String)：改写第 2 个参数 */
    private int hookOverrideString(ClassLoader cl, String className) {
        return 0; // 占位：WebViewClient 走下面的 hookOverrideStringFor
    }

    private int hookOverrideStringFor(ClassLoader cl, String className) {
        Class<?> c = loadClass(className, cl);
        if (c == null) return 0;
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"shouldOverrideUrlLoading".equals(m.getName())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 2 || !ps[1].equals(String.class)) continue;
            final String from = className + "#shouldOverrideUrlLoading(String)";
            try {
                hook(m).setId("usagi-" + from.hashCode())
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                List<Object> args = chain.getArgs();
                                if (args != null && args.size() == 2 && args.get(1) instanceof String) {
                                    String nu = handle((String) args.get(1), from);
                                    if (nu != null) {
                                        Object[] arr = args.toArray();
                                        arr[1] = nu;
                                        return chain.proceed(arr);
                                    }
                                }
                            } catch (Throwable t) {
                                log(Log.WARN, TAG, "override body: " + t);
                            }
                            return chain.proceed();
                        });
                log(Log.INFO, TAG, "hook ok: " + from);
                n++;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook fail " + from + ": " + t);
            }
        }
        return n;
    }

    /** shouldOverrideUrlLoading(WebView, WebResourceRequest)：请求对象不可改，改成主动 loadUrl 并返回 true */
    private int hookOverrideRequest(ClassLoader cl, String className) {
        Class<?> c = loadClass(className, cl);
        if (c == null) return 0;
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"shouldOverrideUrlLoading".equals(m.getName())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 2 || !ps[1].getName().equals("android.webkit.WebResourceRequest")) continue;
            final String from = className + "#shouldOverrideUrlLoading(Request)";
            try {
                hook(m).setId("usagi-" + from.hashCode())
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                List<Object> args = chain.getArgs();
                                Object view = args.get(0);
                                Object req = args.get(1);
                                Object uri = req.getClass().getMethod("getUrl").invoke(req);
                                String nu = handle(String.valueOf(uri), from);
                                if (nu != null && view != null) {
                                    view.getClass().getMethod("loadUrl", String.class).invoke(view, nu);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                log(Log.WARN, TAG, "override req body: " + t);
                            }
                            return chain.proceed();
                        });
                log(Log.INFO, TAG, "hook ok: " + from);
                n++;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook fail " + from + ": " + t);
            }
        }
        return n;
    }


    /** XWeb 内核（pinus）自己的 WebViewClient：页面内跳转 / 302 重定向只能靠这层抓 */
    private int hookPinusClient(ClassLoader cl) {
        Class<?> c = loadClass("com.tencent.xweb.pinus.sdk.WebViewClient", cl);
        if (c == null) return 0;
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            String name = m.getName();
            Class<?>[] ps = m.getParameterTypes();
            final String from = "pinus.WebViewClient#" + name + "/" + ps.length;
            try {
                if ("shouldOverrideUrlLoading".equals(name) && ps.length == 2 && ps[1].equals(String.class)) {
                    hook(m).setId("usagi-" + from.hashCode())
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    List<Object> a = chain.getArgs();
                                    if (a != null && a.size() == 2 && a.get(1) instanceof String) {
                                        String nu = handle((String) a.get(1), from);
                                        if (nu != null) {
                                            Object[] arr = a.toArray();
                                            arr[1] = nu;
                                            return chain.proceed(arr);
                                        }
                                    }
                                } catch (Throwable t) {
                                    log(Log.WARN, TAG, "pinus override body: " + t);
                                }
                                return chain.proceed();
                            });
                    log(Log.INFO, TAG, "hook ok: " + from);
                    n++;
                } else if ("shouldOverrideUrlLoading".equals(name) && ps.length == 2) {
                    hook(m).setId("usagi-" + from.hashCode())
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    List<Object> a = chain.getArgs();
                                    Object view = a.get(0);
                                    Object uri = a.get(1).getClass().getMethod("getUrl").invoke(a.get(1));
                                    String nu = handle(String.valueOf(uri), from);
                                    if (nu != null && view != null) {
                                        view.getClass().getMethod("loadUrl", String.class).invoke(view, nu);
                                        return Boolean.TRUE;
                                    }
                                } catch (Throwable t) {
                                    log(Log.WARN, TAG, "pinus override req body: " + t);
                                }
                                return chain.proceed();
                            });
                    log(Log.INFO, TAG, "hook ok: " + from);
                    n++;
                } else if ("onPageStarted".equals(name) && ps.length == 3 && ps[1].equals(String.class)) {
                    hook(m).setId("usagi-" + from.hashCode())
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    List<Object> a = chain.getArgs();
                                    Object view = a.get(0);
                                    String nu = handle((String) a.get(1), from);
                                    if (nu != null && view != null) {
                                        try {
                                            view.getClass().getMethod("stopLoading").invoke(view);
                                        } catch (Throwable ignored) {
                                        }
                                        view.getClass().getMethod("loadUrl", String.class).invoke(view, nu);
                                        return null; // void 方法，直接吞掉原始导航
                                    }
                                } catch (Throwable t) {
                                    log(Log.WARN, TAG, "pinus onPageStarted body: " + t);
                                }
                                return chain.proceed();
                            });
                    log(Log.INFO, TAG, "hook ok: " + from);
                    n++;
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook fail " + from + ": " + t);
            }
        }
        return n;
    }

    /** 探针：把微信实际用的 WebViewClient 具体类名打出来（下一版据此精确 hook） */
    private int hookWebViewClientSetter(ClassLoader cl) {
        int n = 0;
        for (String cn : new String[]{"com.tencent.xweb.pinus.sdk.WebView", "com.tencent.xweb.WebView"}) {
            Class<?> c = loadClass(cn, cl);
            if (c == null) continue;
            for (Method m : c.getDeclaredMethods()) {
                if (!"setWebViewClient".equals(m.getName())) continue;
                final String from = cn + "#setWebViewClient";
                try {
                    hook(m).setId("usagi-" + from.hashCode())
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    Object cl2 = chain.getArgs().get(0);
                                    if (cl2 != null) {
                                        log(Log.INFO, TAG, "WebViewClient instance: " + cl2.getClass().getName());
                                    }
                                } catch (Throwable ignored) {
                                }
                                return chain.proceed();
                            });
                    log(Log.INFO, TAG, "hook ok: " + from);
                    n++;
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "hook fail " + from + ": " + t);
                }
            }
        }
        return n;
    }


    // ---------------------------------------------------------------- 动态探测
    // 微信的 WebView 可能由动态 ClassLoader 加载（XWeb 内核 dex），Class.forName 拿到的是另一份
    // Class 对象，静态 hook 就不会触发。这里从真实 View 实例反查 Class，再就地 hook。

    private void probeViews(Activity act) {
        try {
            if (act == null) return;
            if (act.getWindow() == null) return;
            final View root = act.getWindow().getDecorView();
            if (root == null) return;
            final String cn = act.getClass().getName();
            for (long delay : new long[]{400, 900, 1800, 3500, 6000}) {
                root.postDelayed(() -> {
                    try {
                        walk(root, 0, cn);
                    } catch (Throwable ignored) {
                    }
                }, delay);
            }
        } catch (Throwable ignored) {
        }
    }

    private void walk(View v, int depth, String actName) {
        if (v == null || depth > 40) return;
        String cn = v.getClass().getName();
        if (cn.toLowerCase().contains("webview")) {
            log(Log.INFO, TAG, "view: " + cn + "  loader=" + v.getClass().getClassLoader()
                    + "  act=" + actName);
            hookDynamic(v.getClass());
            probeClientFields(v, cn);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) walk(g.getChildAt(i), depth + 1, actName);
        }
    }

    /** 从 WebView 实例的字段里把 WebViewClient 实例挖出来（客户端回调才是 302 的落点） */
    private void probeClientFields(Object view, String viewName) {
        for (Class<?> c = view.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String tn = f.getType().getName();
                if (!tn.contains("WebViewClient")) continue;
                try {
                    f.setAccessible(true);
                    Object client = f.get(view);
                    if (client == null) continue;
                    log(Log.INFO, TAG, "client: " + client.getClass().getName()
                            + "  loader=" + client.getClass().getClassLoader()
                            + "  (field " + c.getSimpleName() + "." + f.getName() + " of " + viewName + ")");
                    hookDynamic(client.getClass());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 对任意 Class 就地挂 loadUrl / setWebViewClient / 客户端回调 */
    private void hookDynamic(Class<?> c) {
        if (c == null) return;
        final String cn = c.getName();
        synchronized (hookedClasses) {
            if (!hookedClasses.add(cn)) return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            try {
                Class<?>[] ps = m.getParameterTypes();
                if ("loadUrl".equals(m.getName()) && ps.length >= 1 && ps[0].equals(String.class)) {
                    n += hookArg0(m, "dyn:" + cn + "#loadUrl/" + ps.length);
                } else if ("setWebViewClient".equals(m.getName()) && ps.length == 1) {
                    hook(m).setId("usagi-dyn-setclient-" + cn.hashCode())
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    Object client = chain.getArgs().get(0);
                                    if (client != null) {
                                        log(Log.INFO, TAG, "client(set): " + client.getClass().getName()
                                                + "  loader=" + client.getClass().getClassLoader());
                                        hookDynamic(client.getClass());
                                    }
                                } catch (Throwable ignored) {
                                }
                                return chain.proceed();
                            });
                    n++;
                } else if ("onPageStarted".equals(m.getName()) && ps.length == 3 && ps[1].equals(String.class)) {
                    hook(m).setId("usagi-dyn-onpage-" + cn.hashCode())
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    List<Object> a = chain.getArgs();
                                    Object view = a.get(0);
                                    String nu = handle((String) a.get(1), "dyn:" + cn + "#onPageStarted");
                                    if (nu != null && view != null) {
                                        try {
                                            view.getClass().getMethod("stopLoading").invoke(view);
                                        } catch (Throwable ignored) {
                                        }
                                        view.getClass().getMethod("loadUrl", String.class).invoke(view, nu);
                                        return null;
                                    }
                                } catch (Throwable t) {
                                    log(Log.WARN, TAG, "dyn onPageStarted body: " + t);
                                }
                                return chain.proceed();
                            });
                    n++;
                } else if ("shouldOverrideUrlLoading".equals(m.getName()) && ps.length == 2 && ps[1].equals(String.class)) {
                    hook(m).setId("usagi-dyn-override-" + cn.hashCode())
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    List<Object> a = chain.getArgs();
                                    String nu = handle((String) a.get(1), "dyn:" + cn + "#shouldOverrideUrlLoading");
                                    if (nu != null) {
                                        Object[] arr = a.toArray();
                                        arr[1] = nu;
                                        return chain.proceed(arr);
                                    }
                                } catch (Throwable t) {
                                    log(Log.WARN, TAG, "dyn override body: " + t);
                                }
                                return chain.proceed();
                            });
                    n++;
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "dyn hook fail " + m + ": " + t);
            }
        }
        log(Log.INFO, TAG, "dyn hooks on " + cn + " -> " + n);
    }

    /** Activity 的 Intent：H5 地址常常先装在 intent 里（extra 或 data），这里是最早能改的地方 */
    private int hookActivityIntent(ClassLoader cl) {
        int n = 0;
        try {
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            hook(onCreate).setId("usagi-act-oncreate")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            scanIntent((Activity) chain.getThisObject(), null, "onCreate");
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "intent scan onCreate: " + t);
                        }
                        try {
                            probeViews((Activity) chain.getThisObject());
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "hook ok: Activity#onCreate");
            n++;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook fail Activity#onCreate: " + t);
        }
        try {
            Method onNewIntent = Activity.class.getDeclaredMethod("onNewIntent", Intent.class);
            hook(onNewIntent).setId("usagi-act-onnewintent")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            scanIntent((Activity) chain.getThisObject(),
                                    (Intent) chain.getArgs().get(0), "onNewIntent");
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "intent scan onNewIntent: " + t);
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "hook ok: Activity#onNewIntent");
            n++;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook fail Activity#onNewIntent: " + t);
        }
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).setId("usagi-act-onresume")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            probeViews((Activity) chain.getThisObject());
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "hook ok: Activity#onResume(probe)");
            n++;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook fail Activity#onResume: " + t);
        }
        return n;
    }

    /** 扫描 intent 里的 http(s) 字符串（data + 顶层 String extra），命中规则就原地改掉 */
    private void scanIntent(Activity act, Intent given, String from) {
        Intent it = given;
        if (it == null) {
            try {
                it = act.getIntent();
            } catch (Throwable t) {
                return;
            }
        }
        if (it == null) return;
        String cn = act.getClass().getName();
        String where = from + ":" + cn;

        try {
            String data = it.getDataString();
            if (data != null) {
                String nu = handle(data, where + " data");
                if (nu != null) it.setData(Uri.parse(nu));
            }
        } catch (Throwable ignored) {
        }

        Bundle b;
        try {
            b = it.getExtras();
        } catch (Throwable t) {
            return;
        }
        if (b == null) return;
        scanBundle(it, b, where, 0);
    }

    /** 递归扫 Bundle（最多 2 层），把里面所有 http(s) 字符串按规则改写 */
    private void scanBundle(Intent it, Bundle b, String where, int depth) {
        if (b == null || depth > 2) return;
        for (String key : new ArrayList<>(b.keySet())) {
            Object v;
            try {
                v = b.get(key);
            } catch (Throwable t) {
                continue;
            }
            if (v instanceof Bundle) {
                scanBundle(it, (Bundle) v, where + "/" + key, depth + 1);
                continue;
            }
            if (!(v instanceof String)) continue;
            String s = (String) v;
            if (s.length() < 8 || !(s.startsWith("http://") || s.startsWith("https://"))) continue;
            String nu = handle(s, where + " [" + key + "]");
            if (nu != null) {
                try {
                    b.putString(key, nu);          // 写回它自己所在的 Bundle（支持嵌套）
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "putString " + key + " failed: " + t);
                }
            }
        }
    }


    /** 沿继承链找方法并调用（xweb / pinus 的方法常是继承来的） */
    private static Object invokeDeep(Object target, String name, Class<?>[] types, Object... args) throws Throwable {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                if (!m.isAccessible()) m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(name + " on " + target.getClass());
    }

    // ---------------------------------------------------------------- 双击二维码复制 SGWCMAID

    /** 只在 up.turou.fun 的页面注入：桥 + 双击脚本（分多轮，SPA 渲染慢也能挂上） */
    private void injectQrCopy(String url, Object view) {
        if (url == null || view == null) return;
        if (!url.startsWith("https://up.turou.fun")) return;
        if (!(view instanceof View)) return;
        final View v = (View) view;
        final int id = System.identityHashCode(view);

        // 桥只需装一次；放在页面加载之前效果最好
        synchronized (bridged) {
            if (bridged.add(id)) {
                try {
                    Context ctx = v.getContext();
                    invokeDeep(v, "addJavascriptInterface",
                            new Class<?>[]{Object.class, String.class},
                            new QrCopyBridge(ctx), QrCopyBridge.NAME);
                    log(Log.INFO, TAG, "qr-copy bridge on " + v.getClass().getSimpleName());
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "bridge inject failed: " + t);
                }
            }
        }

        for (long delay : new long[]{900, 1800, 3200, 5200, 8000}) {
            v.postDelayed(() -> {
                try {
                    invokeDeep(v, "evaluateJavascript",
                            new Class<?>[]{String.class, android.webkit.ValueCallback.class},
                            QR_JS, null);
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "qr js inject failed: " + t);
                }
            }, delay);
        }
    }

    /** 统一：替换第 0 个 String 参数 */
    private int hookArg0(Method m, final String from) {
        try {
            hook(m).setId("usagi-" + from.hashCode())
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            List<Object> args = chain.getArgs();
                            Object self = chain.getThisObject();
                            if (args != null && !args.isEmpty() && args.get(0) instanceof String) {
                                String raw = (String) args.get(0);
                                String nu = handle(raw, from);
                                if (nu != null) {
                                    Object[] arr = args.toArray();
                                    arr[0] = nu;
                                    Object r = chain.proceed(arr);
                                    injectQrCopy(nu, self);
                                    return r;
                                }
                                Object r = chain.proceed();
                                injectQrCopy(raw, self);
                                return r;
                            }
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "loadurl body: " + t);
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "hook ok: " + from);
            return 1;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook fail " + from + ": " + t);
            return 0;
        }
    }

    // ------------------------------------------------------------------ 核心判定

    private String handle(String url, String from) {
        if (url == null || url.isEmpty()) return null;
        String hit;
        try {
            hit = UsagiRewriter.rewrite(url);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "rewrite threw: " + t);
            return null;
        }
        if (hit != null) {
            if (prefBool("enable", true)) {
                log(Log.INFO, TAG, "REWRITE [" + from + "] -> " + hit + "   (from " + brief(url) + ")");
                file("REWRITE " + from + " " + brief(url) + " -> " + hit);
                return hit;
            }
            log(Log.INFO, TAG, "MATCH-DISABLED [" + from + "] " + brief(url));
            return null;
        }
        if (prefBool("verbose", false)) logSeen(url, from);
        return null;
    }

    /** 探针：记录所有经过的 URL（去重 + 限量），确认链路到底走哪个方法 */
    private void logSeen(String url, String from) {
        if (url.length() < 8 || url.startsWith("data:") || url.startsWith("javascript:")
                || url.startsWith("about:")) return;
        synchronized (seen) {
            if (seen.size() >= 800 || !seen.add(url)) return;
        }
        log(Log.DEBUG, TAG, "URL [" + from + "] " + brief(url));
    }

    private static String brief(String url) {
        return url.length() <= 260 ? url : url.substring(0, 260) + "…";
    }

    private boolean prefBool(String key, boolean def) {
        if (!DEV) return def;          // 发行版不读偏好，零额外开销
        try {
            if (prefs == null) prefs = getRemotePreferences(PREF_NAME);
            return prefs.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    // ------------------------------------------------------------------ 工具

    private static Class<?> loadClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context currentApp() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            return (Context) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 少量关键事件落一份文件，便于事后取证（写到微信自己的外部缓存目录） */
    private void file(String line) {
        try {
            Context ctx = appCtx;
            if (ctx == null) {
                ctx = currentApp();
                appCtx = ctx;
            }
            if (ctx == null) return;
            File dir = ctx.getExternalCacheDir();
            if (dir == null) return;
            File f = new File(dir, "usagipass.log");
            if (f.length() > 1024 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write((System.currentTimeMillis() + " " + line + "\n").getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }
}
