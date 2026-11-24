# UsagiPass (LSP)

一个把微信内「舞萌玩家二维码」页与微信登录回调重定向到 [UsagiPass](https://up.turou.fun) 的 LSPosed 模块。

作用域仅 `com.tencent.mm`。它把页面地址改在 **URL 进入网络之前**，所以不需要代理、不需要 CA 证书、
不改 VPN/路由——对系统级代理模块零影响。

## 它做什么

微信里点公众号的「玩家二维码」时，官方页面是 `wq.wahlap.net/qrcode/req/<MAID>.html?...`。
本模块在微信进程内把这条 URL 改写成 UsagiPass 的地址（携带 `maid` / `time` / `date`）：

```
https://wq.wahlap.net/qrcode/req/MAIDxxxx.html?l=1730217600
  → https://up.turou.fun/?maid=MAIDxxxx&time=02%3A00%3A26&date=2026%2F11%2F20(%E4%BA%94)
```

同样处理微信 OAuth 回调（`tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx`），
供 UsagiPass 的「更新查分器」使用。

规则与 UsagiPass 服务端 `server/plugins/mitmproxy.ts` 逐行对齐。

## 双击复制登录 ID

UsagiPass 页面上的二维码内容是 `SGWC` + `maid`（即 `SGWCMAID…`）。
模块在 `up.turou.fun` 页面注入一个 JS 桥：**双击二维码图片**即把该 ID 复制到剪贴板（并弹一条 Toast）。
用双击而不是长按，是为了不跟微信自己的「长按图片」菜单抢手势。

## 三层拦截

| 层 | 挂点 | 抓什么 |
|---|---|---|
| L1 Intent | `Activity#onCreate/onNewIntent` | intent.data + 所有 String extra（含嵌套 Bundle）里的 http 串 |
| L2 动态 View | 遍历真实 View 树 → 反查 Class/ClassLoader → 就地 hook | `loadUrl` / `onPageStarted` / `shouldOverrideUrlLoading` |
| L3 静态类表 | `onPackageReady` 时 `Class.forName` | 三套 WebView 基类 + 微信 WebView 子类（兜底） |

为什么需要 L2：微信本体被 Tinker 替换、WebView 由 XWeb 的独立 `DexClassLoader` 加载，
静态 `Class.forName` 拿到的是另一份 Class 对象，静态 hook 不会触发。

## 下载

[Releases](https://github.com/RONUKUIGO/UsagiPass-LSP/releases) → `UsagiPass-LSP-vX.Y.Z.apk`
（发行版：无设置界面、无桌面图标、日志固定关闭）

## 构建

```sh
sh build.sh              # 开发版：有设置页（仅日志开关）+ 桌面图标
sh build.sh --release    # 发行版：无设置页、无桌面入口、无开关
```

发行版签名走外部密钥（不入库）：

```sh
wskeys run -- sh build.sh --release
```

## 安装

1. 安装 APK，在 LSPosed 里启用并勾选「微信」（有多开/多用户时逐个勾）
2. 强制停止微信，重新打开
3. 公众号 → 舞萌|中二 → 玩家二维码

## 日志

```sh
logcat -d -s LSPosedFramework | grep UsagiPass
```

开发版可在设置页打开「详细日志」；发行版日志固定关闭。

## 注意

- 本模块只改写 URL，**不解密任何流量**，不需要安装证书，不申请任何权限。
- 二维码内容是官方 SGWCMAID，安全性等同官方；但请勿外传截图。
- 修改客户端行为的风险自担；详见 [docs/ADAPT-GUIDE.md](docs/ADAPT-GUIDE.md)（含微信升级后的适配流程）。

## 许可

MIT
