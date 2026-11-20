# 微信更新后怎么适配 · UsagiPass (LSP) 维护指南

> 目标：微信升级 / XWeb 内核升级 / Tinker 热补丁之后，用**最短路径**判断"哪一层断了"，并改对地方。

## 0. 先记住这张架构图（三层拦截，互相兜底）

| 层 | 挂在哪 | 抓什么 | 依赖什么 | 失效条件 |
|---|---|---|---|---|
| **L1 Intent 层** | `android.app.Activity#onCreate` / `#onNewIntent` | `intent.data` + 所有 String extra（含嵌套 Bundle，深度≤2）里的 http(s) 串 | 只依赖 framework Activity | 几乎不会失效（微信换实现也不影响） |
| **L2 动态 View 层** | Activity 生命周期后延迟遍历 **真实 View 树**（400/900/1800/3500/6000 ms 五轮），命中类名含 `webview` 的实例 → 反查 `Class` + `ClassLoader` → 就地 hook | `loadUrl` / `onPageStarted` / `shouldOverrideUrlLoading` / `setWebViewClient`，并顺字段挖出 `WebViewClient` 实例一起 hook | 微信的 Activity 里真的挂了 WebView 到窗口 | 页面不在当前进程 / 不用 View 承载 |
| **L3 静态类表** | `onPackageReady` 时 `Class.forName` 后 hook | `android.webkit.WebView`、`com.tencent.xweb.WebView`、`com.tencent.xweb.pinus.sdk.WebView` + 11 个微信 WebView 子类 + 三套 `WebViewClient` | 类由 **app ClassLoader** 加载 | 类被 Tinker / XWeb 的独立 ClassLoader 加载（**本机就是这种情况**） |

**本机实测的坑（重要）**：

```
微信本体  → Tinker: DelegateLastClassLoader[/data/user/0/com.tencent.mm/tinker/patch-*/dex/tinker_classN.apk]
WebView   → DexClassLoader[/data/user/0/com.tencent.mm/app_xweb_data/xweb_1500213/apk/base.apk] → com.tencent.xweb.pinus.PSWebview
```

所以 **L3 静态表在本机一条都不会触发**（别被 "hook ok" 日志骗了，装上≠会调到）；
真正干活的是 **L1（二维码页）** 和 **L2（更新查分器 / 302）**。

## 1. 微信升级后的影响矩阵

| 变化 | L1 | L2 | L3 | 要改什么 |
|---|---|---|---|---|
| **XWeb 内核升级**（`xweb_1500213` → 新版本号，或 `PSWebview` 改名） | ✅ 不受影响 | ✅ 自动适配（遍历真实 View） | ❌ 类名可能变 | **不用改代码** |
| **微信主版本升级**（8.0.x → 9.x） | ✅ 只依赖 framework | ✅ 自动适配 | ❌ 类名/包名可能变 | 先跑探针看日志，通常不用改 |
| **Intent extra key 改名**（`rawUrl` → 别的） | ✅ 因为扫**所有** String extra，不看 key | – | – | 不用改 |
| **二维码页 URL 结构变化**（`/qrcode/req/...` → 新路径） | ❌ 规则不匹配 | ❌ | ❌ | 改 `UsagiRewriter`（见 §3），并核对官方 `server/plugins/mitmproxy.ts` |
| **域名变化**（`wq.wahlap.net` / `wq.sys-all*.cn` / 那几个 IP） | ❌ | ❌ | ❌ | 改 `UsagiRewriter` 的 `SYSALL_HOSTS` / `WAHLAP_HOSTS` |
| **上游改成"原地替换 HTML"而不是 302** | – | – | – | 本方案从根本上失效，只能回到真 MITM（CA + 代理） |
| **Tinker 补丁换了路径**（`patch-xxx`） | ✅ | ✅ | ❌ | 不用改（我们只认实例不认路径） |

## 2. 五步适配流程

### Step 0 · 先取证（30 秒）

```sh
# 模块活着吗？在哪些进程？
su -c 'logcat -d -s LSPosedFramework | grep UsagiPass | grep -E "loaded v|package ready|hooks armed"' | tail -20

# 有没有命中？
su -c 'logcat -d -s LSPosedFramework | grep UsagiPass | grep -E "REWRITE|MATCH-DISABLED"' | tail -20

# L2 看见真实 WebView 了吗？
su -c 'logcat -d -s LSPosedFramework | grep UsagiPass | grep -E "view:|client:|dyn hooks on"' | tail -20
```

### Step 1 · 按症状定位是哪一层断了

| 症状 | 说明 | 去修哪层 |
|---|---|---|
| 二维码页正常、更新查分器不行 | L1 通了、L2 没抓到 302 | L2：看 `view:` / `client:` 是否出现 |
| 两个都不行，但日志有 `view:` | 匹配规则不对（域名/路径变了） | 改 `UsagiRewriter` |
| 两个都不行，日志**没有** `view:` | L2 探针没跑到（Activity 结构变了 / 页面在别的进程） | 先把探针扩展到更多 Activity 或改用别的锚点 |
| 日志完全没有 `loaded v` / `hooks armed` | LSPosed 作用域掉了（微信升级后包名/用户变了？） | LSPosed 里重新勾微信（含分身 999） |
| `hooks armed` 数字变小 | 静态类表里有些类没了 | 不用管（L1/L2 仍在），或按 §3 更新类表 |

### Step 2 · 静态扫描（拿事实，不猜）

```sh
python3 /storage/emulated/0/Tools/LSPilot/scripts/wechat-webview-scan.py \
  "$(su -c 'pm path com.tencent.mm' | sed 's/package://')" --strings
```

输出五段：WebView 继承树 / 覆写 `loadUrl` 的子类 / 导航回调 / H5 容器 Activity / 关键字符串是否还在。
基线报告在 `refs/wechat-8.0.80-webview-scan.txt`，升级后对比一下就知道哪块变了。

> `qrcode/req`、`wahlap` 在 dex 里**本来就找不到**（URL 由服务端下发），所以那几行显示"未找到"是正常的，
> 不要据此判断功能坏了。真正要看的是 `rawUrl` / `shortUrl` / `xweb_` 这些还在不在。

### Step 3 · 改代码（只有这三处可能需要动）

| 文件 | 改什么 | 触发条件 |
|---|---|---|
| `UsagiRewriter.java` | `SYSALL_HOSTS` / `WAHLAP_HOSTS`（域名、IP）、`QR_PATH`（`/qrcode/req/`）、`OAUTH_PATH`（`/wc_auth/oauth/callback/maimai-dx`）、目标参数名（`maid`/`time`/`date`、`r`/`t`/`code`/`state`） | 官方 MITM 逻辑或 URL 结构变了 |
| `ModuleMain.java` → `WEBVIEW_CLASSES` | 补/删微信 WebView 子类 | 只在想让 L3 也覆盖时才需要（L1/L2 通常够） |
| `ModuleMain.java` → `walk()` 过滤条件 | 现在只认类名含 `webview` 的实例 | L2 连 `view:` 都不打时，放宽/换个锚点 |

改之前一定先看官方源码：`https://github.com/TrueRou/UsagiPass` 的 `server/plugins/mitmproxy.ts`，
重定向参数名与时间格式以它为准（本模块逐行对齐过）。

### Step 4 · 构建 + 安装 + 验证

```sh
sh /storage/emulated/0/Tools/LSPilot/projects/usagipass/build.sh        # 产物进 outputs/apk/
su -c 'cp /storage/emulated/0/Tools/LSPilot/outputs/apk/usagipass-vN.apk /data/local/tmp/ws/out/ && \
       pm install -r /data/local/tmp/ws/out/usagipass-vN.apk && rm -f /data/local/tmp/ws/out/usagipass-vN.apk'
```
> 注意：`pm install` **读不了 FUSE 路径**（`/storage/emulated/0/...` 会被 system_server 拒），必须先拷到 `/data/local/tmp`。

装完**强制停止微信再打开**（模块在进程启动时注入），然后：

1. 公众号 → 舞梦|中二 → 玩家二维码 → 期望日志 `REWRITE [onCreate:…MMWebViewUI [rawUrl]]`
2. 卡片左下角小火箭 → 更新查分器 → 期望日志 `REWRITE [dyn:…#onPageStarted]` 或 `#loadUrl`
3. 随手打开一个普通网页（如 `https://example.com`）确认**没被误改**（日志无 REWRITE）
4. 确认 NetProxy 仍在 `ebpf` 后端正常跑（模块与它零交互，但顺手看一眼）

### Step 5 · 收尾

- 版本号 +1（`app/build.gradle.kts` 的 `versionCode`/`versionName` 与 `ModuleMain.VERSION`）
- `MANIFEST.md` 追加一节（日期 / 改了什么 / 实测证据）
- 关掉 verbose（模块设置页），需要排障时再开

## 3. 排障小抄

```sh
# 只在微信进程内看模块日志
su -c 'logcat -d -s LSPosedFramework | grep "com.tencent.mm" | grep UsagiPass'

# 命中落盘的记录（只记改写，1 MB 滚动）
su -c 'tail -20 /storage/emulated/0/Android/data/com.tencent.mm/cache/usagipass.log'

# 网络侧：这次到底有没有直连 wahlap（正常应只有 turou.fun）
tail -c 2000000 /data/adb/modules/netproxy/logs/sing-box.log | grep -aiE "wahlap|sys-all|turou" | tail
```

| 现象 | 最可能原因 | 处理 |
|---|---|---|
| 页面能开但二维码不是你自定义的 | 改写没触发，走的是官方页面 | 看是否有 `REWRITE`；没有→按 Step 1 定位 |
| 显示"无法打开"且日志无 REWRITE | URL 直接连了 wahlap（没被拦） | L2 没抓到 302 → 看 `view:`/`client:` 日志 |
| 改写后页面白屏 | 目标 URL 参数不对（时间/编码） | 用 `UsagiRewriter` 的单测脚本核对输出 |
| 微信变卡 | 一般不是本模块（每页 <0.1 ms）；先看 verbose 是否开着 | 关闭 verbose |
| LSPosed 里勾了但没注入 | 微信有分身/多用户 | user 0 和 999 都要勾 |

## 4. 回滚

```sh
su -c 'pm uninstall me.yun.usagipass'      # 彻底移除，微信立即恢复原样
```
模块只做 Java 层 URL 字符串替换，卸载即复原，**没有残留网络配置、没有 CA、没有路由改动**。
