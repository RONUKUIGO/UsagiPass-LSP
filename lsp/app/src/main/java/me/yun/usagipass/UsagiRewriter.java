package me.yun.usagipass;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * UsagiPass URL 重写规则（纯 Java，无 Android 依赖，便于在沙箱里单元验证）。
 *
 * 逻辑逐行对齐 UsagiPass 服务端 server/plugins/mitmproxy.ts：
 *   1) host ∈ SYSALL_HOSTS 且 path 以 /qrcode/req 开头
 *      例: http(s)://wq.sys-all.cn/qrcode/req/MAID241020A01.html?l=1730217600&t=E8889E
 *      → https://up.turou.fun/?maid=MAID241020A01&time=HH:mm:ss&date=YYYY/MM/DD(周X)
 *      time/date 由 query 里的 l（unix 秒）按 UTC+8 换算
 *   2) host ∈ WAHLAP_HOSTS 且 path 以 /wc_auth/oauth/callback/maimai-dx 开头
 *      → https://up.turou.fun/wechat/callback?r=&t=&code=&state=
 *   3) 其它一律返回 null（不改写）
 */
public final class UsagiRewriter {

    public static final String BASE = "https://up.turou.fun";

    private static final Set<String> SYSALL_HOSTS = new HashSet<>(Arrays.asList(
            "42.193.74.107", "129.28.248.89", "43.137.91.207", "81.71.193.236",
            "43.145.45.124", "43.137.89.90",
            "wq.wahlap.net", "wq.sys-all.cn", "wq.sys-allnet.cn"));

    private static final Set<String> WAHLAP_HOSTS = new HashSet<>(Arrays.asList(
            "152.136.21.46", "tgk-wcaime.wahlap.com"));

    private static final String[] WEEKDAYS = {"一", "二", "三", "四", "五", "六", "日"};
    private static final long SHANGHAI_OFFSET_SECONDS = 8L * 3600L;
    private static final String QR_PATH = "/qrcode/req/";
    private static final String OAUTH_PATH = "/wc_auth/oauth/callback/maimai-dx";

    private UsagiRewriter() {
    }

    /** @return 改写后的 URL；不需要改写时返回 null */
    public static String rewrite(String url) {
        if (url == null) return null;
        String u = url.trim();
        int sep = u.indexOf("://");
        if (sep < 0) return null;

        String rest = u.substring(sep + 3);
        int hash = rest.indexOf('#');
        if (hash >= 0) rest = rest.substring(0, hash);

        String authority, pathAndQuery;
        int slash = rest.indexOf('/');
        if (slash < 0) {
            authority = rest;
            pathAndQuery = "/";
        } else {
            authority = rest.substring(0, slash);
            pathAndQuery = rest.substring(slash);
        }

        String host = authority;
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        if (!host.startsWith("[")) {
            int colon = host.indexOf(':');
            if (colon > 0) host = host.substring(0, colon);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return null;

        int qm = pathAndQuery.indexOf('?');
        String path = qm < 0 ? pathAndQuery : pathAndQuery.substring(0, qm);
        String query = qm < 0 ? "" : pathAndQuery.substring(qm + 1);

        if (SYSALL_HOSTS.contains(host) && path.startsWith("/qrcode/req")) {
            return buildQrUrl(path, query);
        }
        if (WAHLAP_HOSTS.contains(host) && path.startsWith(OAUTH_PATH)) {
            return buildOAuthUrl(query);
        }
        return null;
    }

    private static String buildQrUrl(String path, String query) {
        int start = path.indexOf(QR_PATH);
        int end = path.indexOf(".html");
        if (start < 0 || end < 0 || end <= start) return null;
        String maid = path.substring(start + QR_PATH.length(), end);

        long seconds = parseLongSafe(getQueryParam(query, "l"), 0L);
        String[] timeDate = buildShanghaiTimeDate(seconds);

        StringBuilder sb = new StringBuilder(BASE);
        sb.append("/?maid=").append(enc(maid))
          .append("&time=").append(enc(timeDate[0]))
          .append("&date=").append(enc(timeDate[1]));
        return sb.toString();
    }

    private static String buildOAuthUrl(String query) {
        StringBuilder sb = new StringBuilder(BASE).append("/wechat/callback");
        sb.append("?r=").append(enc(nullToEmpty(getQueryParam(query, "r"))))
          .append("&t=").append(enc(nullToEmpty(getQueryParam(query, "t"))))
          .append("&code=").append(enc(nullToEmpty(getQueryParam(query, "code"))))
          .append("&state=").append(enc(nullToEmpty(getQueryParam(query, "state"))));
        return sb.toString();
    }

    /** 对齐 mitmproxy.ts 的 buildShanghaiDate：把 unix 秒 +8h 后按 UTC 取年月日时分秒 */
    static String[] buildShanghaiTimeDate(long seconds) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.setTimeInMillis((seconds + SHANGHAI_OFFSET_SECONDS) * 1000L);
        String time = String.format(Locale.US, "%02d:%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
        int jsDay = c.get(Calendar.DAY_OF_WEEK) - 1;      // 0=周日 … 6=周六
        int idx = (jsDay + 6) % 7;                        // 0=周一 … 6=周日
        String date = String.format(Locale.US, "%04d/%02d/%02d(%s)",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), WEEKDAYS[idx]);
        return new String[]{time, date};
    }

    static String getQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (!k.equals(key)) continue;
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                return URLDecoder.decode(v, "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                return v;
            }
        }
        return null;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static long parseLongSafe(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            try {
                double d = Double.parseDouble(s.trim());
                if (Double.isNaN(d) || Double.isInfinite(d)) return def;
                return (long) d;
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
    }
}
