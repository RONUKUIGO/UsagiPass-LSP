package me.yun.usagipass;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

/**
 * 页面 JS ↔ 模块的桥：只提供一个方法（把 SGWCMAID 放进剪贴板）。
 * 只在 host 为 up.turou.fun 的页面注入，避免把接口暴露给任意站点。
 */
final class QrCopyBridge {

    static final String NAME = "UsagiPassBridge";

    private final Context ctx;

    QrCopyBridge(Context ctx) {
        this.ctx = ctx;
    }

    @JavascriptInterface
    public void copy(String text) {
        if (text == null || text.isEmpty()) return;
        final String v = text.length() > 160 ? text.substring(0, 160) : text;
        Log.i(ModuleMain.TAG, "COPY " + v);
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Context app = ctx;
                    try {
                        if (ctx.getApplicationContext() != null) app = ctx.getApplicationContext();
                    } catch (Throwable ignored) {
                    }
                    ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("SGWCMAID", v));
                    Toast.makeText(app, "已复制登录ID", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
