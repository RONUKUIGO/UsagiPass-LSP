package me.yun.usagipass;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 模块设置页（只写 SharedPreferences，LSPosed 通过 XposedInterface.getRemotePreferences 同步给微信进程）。
 * 注意：改完开关不需要重启微信 —— 每次 URL 匹配都会重新读一次偏好。
 */
public class MainActivity extends Activity {

    static final String PREF_NAME = "usagipass";
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        root.addView(title("UsagiPass (LSP)  v" + ModuleMain.VERSION));
        root.addView(body("作用域：com.tencent.mm（微信，含多开/多用户）\n"
                + "功能：把微信里舞萌二维码页 / 微信 OAuth 回调页的 URL 改写到 up.turou.fun\n"
                + "不改网络栈：无 VPN、无代理、无 CA 依赖，对内核级代理模块零影响。"));

        root.addView(sw("enable", "启用 URL 改写", true));
        root.addView(sw("verbose", "详细日志（记录所有经过的 URL，排障用）", false));

        root.addView(body("在 LSPosed 里给它勾上「微信」→ 强制停止并重新打开微信 → 进公众号点「玩家二维码」。\n\n"
                + "日志：logcat tag = UsagiPass；文件：/storage/emulated/0/Android/data/com.tencent.mm/cache/usagipass.log"));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private Switch sw(final String key, String label, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(15f);
        s.setPadding(0, dp(14), 0, dp(14));
        s.setChecked(sp.getBoolean(key, def));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                sp.edit().putBoolean(key, v).apply();
            }
        });
        return s;
    }

    private TextView title(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(20f);
        v.setGravity(Gravity.START);
        v.setPadding(0, 0, 0, dp(12));
        return v;
    }

    private TextView body(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13.5f);
        v.setTextColor(Color.GRAY);
        v.setPadding(0, dp(6), 0, dp(10));
        return v;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
