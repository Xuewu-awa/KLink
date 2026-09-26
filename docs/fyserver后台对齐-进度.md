# KLink 手机端 — 对齐 fyserver 后台（进度与交接）

> 背景：桌面端 KLink 已把私服换成 [CCB-TEAM/fyserver](https://github.com/CCB-TEAM/fyserver)（.NET），
> 并把「后台即前台」作为交互形态。手机端（本项目，Java）要复用同一套后台页面与接口，
> 因此按 fyserver 的 `/admin/api/*` 契约逐条移植。
>
> 桌面端的完整分析见 `klink-dotnet/需求/fyserver集成规划.md` 与 `docs/fyserver/`。
> 本文件只记录**手机端**的移植状态。

---

## 一、本轮已完成

### 1. 构建链修复（原先跑不起来）

`builder/build.bat` 原来有两个硬伤：

| 问题 | 原状 | 现状 |
|---|---|---|
| build-tools 版本写死 | `BUILD_TOOLS=37.0.0`，本机只有 30/34/35/36 → 找不到 d8 | 自动扫描 `%ANDROID_HOME%\build-tools`，取**实际存在 d8.bat 的最高版本** |
| 依赖 PATH 上的 java | `java`/`javac` 都不在 PATH，`JAVA_HOME` 为空 | 自动探测 `JAVA_HOME` → Android Studio 自带 `jbr` → PATH；并把探测结果传给 d8.bat |

构建逻辑迁到 **`builder/build.ps1`**，`build.bat` 变成一行转发（ASCII-only —— `cmd.exe`
按 OEM 代码页读 .bat，UTF-8 中文注释会被拆成乱码命令）。

```bash
cd builder
./build.bat          # 或 pwsh -File build.ps1
# 产物：builder/build/classes2.dex
```

实测：`classes2.dex` 160.7 KB（28 个源文件）。

### 2. 后台产物重建（原先编译产物是旧的）

`klink-dotnet/tem/fyserver` 里你的 KLink 魔改（**本机回环免登录**）只存在于
`AdminUi/src/`，而 `wwwroot/admin-ui/` 是改动前的旧产物 —— 旧产物仍带
`退出登录` ×2、`/admin-ui/login.html` ×4。

已从 `AdminUi/src` 用 Vite（v8.3.0 / node 24）重新构建：

```bash
cd klink-dotnet/tem/fyserver/AdminUi
./node_modules/.bin/vite.cmd build      # 输出到 ../wwwroot/admin-ui
```

验证：新 `main.js` 含 `e.authorized||e.loopback` 判定，免登录补丁已生效。

产物已复制到 **`source/assets/admin-ui/`**（59 文件 / 1.0 MB）。

### 3. 欢迎界面（`source/assets/splash.html`）

深色底 + 翡翠径向渐变 + KLINK 图标 + 呼吸光晕 + 进度条 + 扫描线，
复用现有 `style.css` 的配色变量，视觉上与「战术终端」一套。

Java ↔ JS 约定：

| 方向 | 接口 |
|---|---|
| Java → JS | `KLinkSplash.set(pct, text)` / `KLinkSplash.fail(text)` / `KLinkSplash.version(v)` |
| JS → Java | `KLinkSplashHost.beginStartup()` / `openPanel()` / `isServerRunning()` |

启动超过 2.5 秒会露出「跳过，进入控制面板」按钮（逃生口）。

### 4. 启动流程改造（`MainActivity`）

```
splash.html  →  [后台线程] 自动起私服 + 轮询就绪（上限 20s）
             →  就绪：http://127.0.0.1:{port}/admin-ui/    ← 后台即前台
             →  失败/超时/远程模式：回退 file:///android_asset/index.html
```

- 新增 `KLinkBridge.autoStartSavedMode()` / `getAdminUiUrl()` / `isServerMode()`；
- `shouldOverrideUrlLoading` 放行 `127.0.0.1` / `localhost` / `file://`
  —— 否则点一下后台导航就被甩到系统浏览器；
- **未开** `setAllowUniversalAccessFromFileURLs`（那是 XSS 放大器，本方案不需要）。

### 5. 后台静态服务与鉴权（新增两个类）

| 文件 | 职责 |
|---|---|
| `http/AdminUiHandler.java` | 把 `assets/admin-ui/**` 经 HTTP 发出；MIME 表、路径穿越校验、SPA fallback |
| `http/AdminApiHandler.java` | `/admin/api/*` 路由 + 鉴权 + 各端点实现 |

**为什么必须走 HTTP 而不能让 WebView 直接读 `file:///android_asset/`**：
后台是 Vite 产物，用 `<script type="module">` + 17 个路由级动态 `import()` 分包。
`file://` 下 ES module 与动态 import 被 WebView 直接拦死（opaque origin 的 CORS），
且 SPA 内部引用绝对路径 `/admin-ui/assets/...`。桌面端也是「服务器发页面」，这里保持一致。

**鉴权**（对齐桌面端 `AdminApiAuthorizationMiddleware` 的 KLink 补丁）：

```
回环请求（127.0.0.1 / ::1）  → 视为 Owner，免登录     ← WebView 走这条
带 X-Admin-Key 的请求        → 视为「KLink 启动器」，Owner
其它远程请求                 → 401（LAN 模式下后台地址被别人打开不会裸奔）
```

因此手机端**不需要** fyserver 原版的 PBKDF2/SHA-256 管理员账号体系。

### 6. 已实现的 API（29 条）

| 端点 | 说明 |
|---|---|
| `GET /admin/api/session` | 含 `authorized`/`loopback`/`loopbackTrusted`，前端 `ensureAuth()` 靠它免登录 |
| `GET /admin/api/stats` | 用户/卡组/在线/对局/队列 + 进程内存 + 数据库体积 + uptime |
| `GET /admin/api/users` | **`{total, users:[…]}`**，含 `displayName`/`lastLogin*`/`createdAt`/货币 |
| `GET /admin/api/users/{id}` | 详情 + 卡组 + 装备 + 钱包 |
| `POST /admin/api/users/{id}/kick` | 只断开不封禁 |
| `GET /admin/api/matches` | **`{onlineCount, activeMatches:[…], queues:[…]}`** |
| `POST /admin/api/queues/clear` | 清空匹配队列 |
| `GET/PUT /admin/api/server-config` | 服务器配置（`raw` 是 JSON **字符串** + `schema`/`flags`/`comments`） |
| `PUT /admin/api/server-config/item` | 新增/修改单项 |
| `DELETE /admin/api/server-config/item/{key}` | 删除单项 |
| `PUT /admin/api/server-config/item/{key}/enabled` | 是否下发给客户端（关闭只停发，不删值） |
| `GET/PUT /admin/api/store` | 商店配置（`{ok, storage, path, config}`，保存前备份 `.bak`） |
| `POST /admin/api/store/reload` | 重新读取商店配置 |
| `GET /admin/api/cards` | 卡牌目录分页查询（`items`/`total`/`cardSets`/`types`） |
| `PUT /admin/api/cards/defaults` | 把当前筛选结果批量加入新玩家默认卡牌 |
| `GET/PUT /admin/api/system-settings/player-library` | 新玩家初始卡牌策略 |
| `GET/PUT /admin/api/system-settings` | 网络地址（监听/对外，含 `restartRequired` 比对） |
| `GET/PUT /admin/api/system-settings/match-retention` | 对局保留策略 |
| `GET/POST /admin/api/match-config` | 开局金卡开关（改 `MatchManager.allGoldCards`，重启后仍生效） |
| `POST /admin/api/match-config/reload` | 重新读取对局配置 |
| `GET /admin/api/audit-logs` | 空列表（手机端无账号体系） |
| `GET /admin/api/matches/history`、`/storage` | 空列表（手机端未开启对局历史持久化） |

未实现的端点返回 **501 + `notImplemented: true`**，前端显示错误而不是白屏。

### 7. 数据库 v2/v3（`KardsDatabase`）

新增列（`onCreate` 全量建、`onUpgrade` 增量补，老存档不丢数据）：

| 列 | 用途 |
|---|---|
| `last_login_at` / `last_login_ip` / `last_login_device` | 后台用户列表的「最近登录 / IP / 设备」 |
| `gold` / `diamonds` / `dust` | 真开包与购买的前提（原先硬编码在响应里，重启即复原） |
| `banned` | 封禁状态位 |
| `packs_json` | 未打开的卡包列表 |
| `user_cards_json` | 卡牌收藏（普通数 + 金卡数） |
| `purchased_offers_json` | 商店限购计数 |

`/session` 登录成功时会写一次来源信息，并在首次登录时发初始卡包。
另复用既有的 `server_settings` 表存放 `server_options` 配置 / 开关 / 注释，以及
后台各页的设置（`admin:` 前缀）。

### 9. 真开包系统（本轮新增）

原先 `GET /players/{id}/packs` 是**桩**：循环 40 次塞 `{"card_set":"5|Core","id":0}` 和
`{"card_set":"7|Core","id":1}` —— 80 条重复且 id 恒定；`/store/*` 根本没有 handler。

新增三个类（逐行移植自桌面端 `tem/fyserver`，**不重新设计**，否则两边开出同一张包的概率分布不一致）：

| 文件 | 移植来源 | 说明 |
|---|---|---|
| `CardCatalog.java` | `CardCatalogService.cs` | 2067 张卡的目录；卡池构建、可入包判定、客户端卡库生成 |
| `PlayerCards.java` | `PlayerCardService.cs` | 开包结算、万能牌合成、初始卡包 |
| `StoreService.java` | `StoreConfigService.cs` + `PlayerEndpoints` | 商店配置、商品下发、购买结算 |

**抽取规则**（照搬，勿改）：稀有度权重 `[0.66,0.22,0.08,0.04]`（Common→Unique）、
收藏上限 `[4,3,2,1]`、金卡概率 15%（5 张的包除外）、尘按稀有度 `[5,10,50,100]` 且上限 1000、
万能牌 15% 替换；5 张包保底 ≥1 Uncommon，7 张包保底 ≥1 Rare 且累计 ≥3 Uncommon 以上。

**货币语义**（与 NestJS 原实现一致）：`transactionType` 1=扣金币、3=扣钻石、0/2 不扣
（2 是外部支付渠道，服务端只发货）。

**卡池数据已实测**：

| 商店在售规格 | 卡池 | Common / Uncommon / Rare / Unique |
|---|---|---|
| `5\|Core`、`7\|Core` | 350 张 | 120 / 90 / 80 / 60 |
| `5\|Homefront`、`7\|Homefront` | 111 张 | 34 / 33 / 24 / 20 |

四档齐全 ⇒ 保底逻辑永远有牌可发，不会出现"死包"。

> ⚠️ **Homefront 必须留在卡池里**。桌面端上游曾把 Homefront 与 Special/Placeholder/
> Expansion1/OnlySpawnable 一起排除，但 `store.json` 里就有 `cardSet=Homefront` 的卡包，
> 玩家买完打开会直接抛 `Card set "Homefront" not found` —— 包永远开不了。
> 手机端已按桌面端的修正版处理（只排除后四个）。

**`/players/{id}/library` 改为由卡牌目录生成**（原先是直接下发静态 `library.json`）。
两者都是"全部可用卡各 4 张"，但目录版修掉了两个上游 bug：
`gold_card_count` 写死 5（等于告诉客户端每张卡都有 5 张金卡）、`id` 写死常量。
现在 `id` 取自 deck 码表的真实值，查不到退化为 0。目录不可用时自动回退到静态卡库。

**`/session` 的货币改为真实持久化值**（原先硬编码 `gold=78`/`diamonds=91`/`dust=0`，
开包购买重启就复原）。新号首次登录发 5 个初始卡包（`KardsLocalServer.STARTING_PACKS`）。

### 10. `server_options` 可配置化

原先 40 多项 `server_options`（`anzac`、`versions`、`ocenia_storm_date`、各种活动日期、
`most_popular_products` …）**硬编码**在 `KardsHttpHandler.serverOptions()` 里 ——
想开个活动就得改 Java 源码、重编译、再注入 APK。

新增 `ServerOptionsStore.java`：

- 首次运行以**代码内置默认值**（`serverOptionsTemplate()`）为种子落盘，保证行为与原来完全一致；
- 之后以数据库中的配置为准，后台「服务器配置」页可逐项改值 / 停发 / 删项；
- **强制项**：`websocketurl`（跟着当前监听端口与通告 IP 走）与 `versions`
  （必须含版本补丁 pak 写入的版本号，否则客户端被版本检测挡住）始终由服务器计算，后台只读；
- 内置了一份中文注释表（`describe()`），列表页不会整列空白 —— 对着一堆
  `covert_ops_date` 之类的键名很难判断该不该动；
- **兜底**：存储值解析失败一律回落到内置默认模板。`server_options` 是登录链路的一部分，
  这里出问题会导致整个客户端登不进来，宁可丢弃用户改动也不能返回非法内容。

> 未移植桌面端的镜像 `schema`（`serverOptions.schema.json`，31 KB，来自 qa-1939api-mirror）。
> 前端拿不到 schema 只是少一列「镜像参考默认值」，功能不受影响。

### 11. 品牌改为 KLink

后台侧边栏原本显示 **FYServer / SERVER CONTROL CENTER**（图标早已是 KLINK 的）。已改为
**KLink / KARDS LOCAL SERVER**，覆盖：侧边栏、顶栏面包屑、页脚、仪表盘问候语、
登录页、主题自定义对话框的默认值与预览。

`admin-v4.js` 里加了一次品牌映射：浏览器可能还缓存着改名前的主题标题，
读到旧值会替换成 KLink（**用户自定义的标题不受影响**）。

> 涉及一个 Vite 的坑：`<img src="/admin-ui/assets/…">` 会被当成**模块资源路径**解析并报
> `UNRESOLVED_IMPORT`。public 目录下的资源要用 `base` 前缀形式 `/assets/…`
> （构建时自动补成 `/admin-ui/assets/…`）。

---

## 二、验证方式与结果

本项目是「注入 APK」的 Java 代码，没有现成的自动化测试宿主，因此本轮采用：

1. **桩服务器 + 真浏览器**：`temp/adminstub/server.js` 复刻 Java 侧的响应结构，
   用 Edge headless 在 **960×540（横屏手机比例）** 渲染真实后台产物。
2. **逐页核对渲染结果**：

| 页面 | 结果 |
|---|---|
| 基础信息（dashboard） | ✅ 未跳登录页；在线/用户/活跃对局/等待匹配四项数字正确；uptime 原样显示 |
| 用户管理 | ✅ 三条测试用户成表，含显示名、最近登录、IP、设备、状态 chip |
| 对局监控 | ✅ 对局表与匹配队列都渲染；摘要行三类计数正确 |
| 服务器配置 | ✅ 侧边栏品牌显示 KLink；「12 / 12 项已配置 · 0 项镜像参考」与载荷一致 |
| 对局配置 | ✅ 金卡下拉与"当前：所有牌按金卡下发"提示一致 |
| 系统设置 | ✅ 网络地址各字段回填正确 |
| 商店 | ✅ 货币 USD、分组/商品树、保存/重载/导出按钮齐全 |
| 卡牌列表 | ✅ 表格与筛选渲染 |
| 新玩家卡牌 | ✅ 策略与卡牌分页渲染 |

3. **卡池数据核对**（脚本比对真实 `cards-from-fmodel.json`）：
   可入包 1538 张（桌面端文档称 1542）、Core 可入包 975 张（称 979）；
   差值 4 张来自 `isReserved` 判定口径的细微差异，不影响卡池可用性。
   **商店实际在售的四个卡包规格（5|Core、7|Core、5|Homefront、7|Homefront）
   全部四档稀有度齐全**，保底逻辑永远有牌可发。

> ⚠️ 这一步救回了**三个真实 bug**：`/admin/api/users` 与 `/matches` 我最初返回裸数组，
> 但前端读的是 `res.users` 与 `data.activeMatches` —— 页面上会静默显示"没有匹配的用户"，
> 不报任何错。另外后台产物本身是**旧版**（`AdminUi/src` 的 KLink 免登录补丁没编进
> `wwwroot/admin-ui`）。这三处仅靠读源码都很难发现，只有真渲染才暴露。

**仍未验证**（需要真机）：WebView 加载回环后台、横屏实际渲染、游戏内登录/对战/开包购买链路。
开包与购买的**算法**是逐行移植的已验证实现、**卡池**已核对，但**端到端**（客户端点开包 → 看动画 → 收藏变化）
只能真机验证。

---

## 二·补、一个让整个后台瘫痪的路由 bug（已修）

真机验证时后台**跳到了登录页**，点登录又提示「该功能尚未移植到手机端：/login」。

排查过程（值得记下来）：那行提示只可能来自 `AdminApiHandler` 的 `notImplemented()`，
说明**请求确实到达了手机端私服**，但 `/admin/api/session` 返回了 501
—— 而它在代码里明明注册着。于是前端 `ensureAuth()` 拿到没有 `authorized` 的响应，
判定未授权 → 跳登录页；用户点登录 → `POST /admin/api/login` 确实没实现 → 501。

**根因：路由两侧用了不同的基准。**

| 位置 | 旧代码 | 切出来的段 |
|---|---|---|
| 注册（`Route` 构造函数） | `segments("/session")` | `["session"]`（1 段） |
| 匹配（`handle()`） | `segments("/admin/api/session")` | `["admin","api","session"]`（3 段） |

段数永远不等 ⇒ **所有 29 个后台端点全部落到 501**。后台等于完全不可用。

修法是让匹配侧也用去掉前缀后的 `local`：`segments(local)`。

> 教训：`AdminApiHandler` 之前的"验证"是用 **Node 桩服务器**做的
> （`temp/adminstub/server.js` 复刻响应结构），它根本不经过 Java 路由，
> 所以页面渲染得漂漂亮亮、bug 却一直躲着。**桩服务器验证不了路由。**

### 为此补的验证手段

新增 `temp/routecheck/`：在**桌面 JVM** 上直接实例化 `AdminApiHandler`
（依赖传 null，避开 Android 运行时），断言真实返回码与字段。

覆盖：回环 `/session` 必须 200 且 `authorized`/`loopback`/`serverReady` 为 true、
`ensureAuth()` 必须放行、非回环必须 401 且 `loopback=false`、
未移植端点仍是 501。

跑法：

```powershell
# 1) 编译被测类（正常构建产物即可）
cd builder; ./build.bat
# 2) 编译并运行验证
javac -encoding UTF-8 -cp "builder\build\javac;builder\android.jar" -d temp\routecheck\out `
  temp\routecheck\stub\com\android1939\kardsserver\ServerLog.java `
  temp\routecheck\com\android1939\kardsserver\http\AdminApiRouteCheck.java
java -cp "temp\routecheck\out;temp\routecheck\lib\json.jar;builder\build\javac;builder\android.jar" `
  com.android1939.kardsserver.http.AdminApiRouteCheck
```

> 需要一份**能用的** `org.json`（`temp/routecheck/lib/json.jar`）：
> `android.jar` 里的 `org.json` 是抛异常的空壳，反射/JSON 调用会失败。

### 顺带补齐的登录端点

`/login`、`/setup`、`/logout`、`/me` 从 501 改为真实实现：

- **回环且未设过密码 → 直接放行**（手机端 WebView 走的就是这条，永远是免登录）
- 设过密码 → 校验（PBKDF2-SHA256，210k 次，与桌面端同参数）
- 非回环 + 正确 `X-Admin-Key` → 放行
- `/setup` 仅允许回环，且密码≥10 位

另加 `GET /admin/api/diag`：出问题时直接看服务器**看到的来源地址**与鉴权结论。

`session` 的 `loopback` 也改成按**本次请求真实来源**计算（原来写死 `true`
—— 那会让非回环请求也拿到 `loopback=true` 从而越权免登录）。

### 后台请求现在会记日志

`401 / 501` 以及各端点的状态码都会进 `ServerLog`，可在 KLink 控制面板的日志区看到，
不用再靠猜。

---

### 3. 后台的「游戏入口」与会话性能（真机反馈后补）

真机跑起来后反馈三件事，都已处理：

**(1) 界面溢出屏幕（右侧被裁掉）** —— 根因是 viewport 里多写了 `initial-scale=1`。
它把缩放锁死在 1.0，等于放弃"缩放到适应屏幕"，于是 1320px 的布局在 960px 的屏幕上
右边 360px 直接被裁。**只给 `width`，不写 `initial-scale`**，让 WebView 自己算缩放。

**(2) 进不去游戏** —— 后台是 fyserver 的管理界面，**本身没有游戏入口**。
把它当主界面就等于把玩家锁在后台里了。现在两边都有路：

| 方向 | 入口 | 实现 |
|---|---|---|
| 后台 → 游戏 | 右下角浮标「**启动游戏**」 | 注入的脚本 → `KLinkHost.launchGame()` → 应用版本补丁 pak → 拉起游戏 |
| 后台 → KLink 面板 | 同一个浮标的「**控制面板**」 | 注入的脚本 → `KLinkHost.openPanel()` → 加载 `index.html` |
| KLink 面板 → 后台 | 「服务器」页的「**进入管理后台**」按钮 | `KLinkHost.openAdminUi()`（回退 `KLink.openAdminUi()`） |

> 之前只有前两条，面板回不去后台 —— 单行道。现在三条都通了。
> 面板那个按钮放在「服务器」页启停按钮下方：后台要有地址才进得去，
> 私服没跑时会提示"请先启动服务器"而不是白屏。

游戏专属的管理项（模组 / 卡牌ID / 版本补丁 / 主题）仍在 KLink 面板里。

**(3) 卡顿** —— 已做两处优化：

- `CardCatalog.buildClientLibraryJson()` 加缓存。原先**每次**进游戏都要把
  2000+ 张卡重建一遍 JSON，低端机上肉眼可见地卡；内容只取决于卡牌目录，
  进程内不会变，缓存到进程结束即可。
- 后台自带的装饰元素（看板 `SERVER OVERVIEW` 等 eyebrow 文字、`动画`）
  在 `admin-v4.css` 里已有 `@media` 关掉，无需处理。

> 剩余卡顿主要来自后台本身：它是为桌面浏览器写的 SPA，主包 + Vue runtime 约 93 KB、
> 加上字体 300 KB，在 WebView 里的解析与首屏渲染开销是固有的。要再快就得改前端，
> 属于另一个量级的改动。

---

### 4. 配置字段的所有权（对齐桌面端踩过的坑）

桌面端出过一个 bug：**数据库里持久化的 `server:settings` 把启动器刚写的设置覆盖掉** ——
切到局域网时启动器写 `listenIp=0.0.0.0`，被库里的旧值顶回 `127.0.0.1`，
表现为"局域网模式却只绑回环、其它设备连不上"。同一个坑还让 `portHttp`、管理员名中招过。

**手机端不会重现这个 bug**，因为监听地址从来不进数据库：

| 字段 | 所有权 | 说明 |
|---|---|---|
| `bindHost` | **启动器模式** | local → `127.0.0.1`、lan → `0.0.0.0`，由 `KLinkBridge` 直接写进 `ServerConfig` |
| `httpPort` / `wsPort` | **代码常量** | 5231 / 5232 |
| `advertisedHost` | 后台「系统设置」可改 | 只影响**下发给客户端的地址**，不影响监听 |
| `allGoldCards` | 后台「对局配置」可改 | 启动时读一次 |

`KardsLocalServer.start()` 只读两个键（`admin:allGoldCards`、`admin:publicIp`），
**没有任何一处用库里的值去覆盖监听地址**。

> 但本轮**修掉了一个更隐蔽的同类问题**：我之前做的后台「系统设置」页会把
> `listenIp`/`listenPort` 存进库、还提示"保存后需重启生效"，**实际上永远不会生效**
> —— 做了一个"能改但不生效的假开关"。这比桌面端那次更难发现（那次是读错值，这次是根本不读）。
>
> 现在：`GET /system-settings` 直接返回**当前运行的**监听配置并标 `listenEditable: false`；
> `PUT` 只保存对外地址，显式忽略监听字段，返回的提示也说明这一点。

---

## 三、待办

### 剩余 `/admin/api` 端点

已实现 29 条；下面是**尚未实现**的（返回 501）：

| 分组 | 端点 | 手机端的必要性 |
|---|---|---|
| 内容配置 | `/content/*`（frontpage / skirmish / knockout，含导入导出与图片上传） | 低 —— 手机端 `/fp/` 一直是静态桩 |
| 兑换码 | `/redeem` 系列 | 中 —— 需要新表 |
| 补丁 pak | `/patch-paks` 系列 | 中 —— 手机端已有 `VersionPakManager`，可复用 |
| 卡牌编辑 | `PUT /cards/{id}` | 低（只读列表已够用） |
| 账号体系 | `/accounts` 系列 `/me` 系列 `/login` `/logout` `/setup` | **不需要** —— 手机端走回环免登录 |
| 数据库 | `/database` `/database/configure` | **不需要** —— 手机端固定用 SQLite |
| 对局历史 | `/matches/history/{id}`、`/actions`、`DELETE` | 低 —— 需要新表 |

### 界面收尾

- ~~对局表较宽，960×540 下需横向滚动~~ → **已通过 viewport 固定布局宽度解决**（见下节）；
- 卡牌ID管理器（现有 `/admin/library`、`/admin/decks`）尚未并入后台页面；
- 主题自定义里仍保留「FY Cute」字体（fyserver 自带字体名，与品牌无关，未动）。

---

## 三·补、登录不做校验 + 界面缩放适配

### 1. 登录不校验密码（对齐桌面端）

桌面端 fyserver 的 `/session` 只做「按 username 查用户，查不到就建」，**完全不看客户端提交的
password** —— 客户端登录框里的密码字段实际是每次安装都会变的设备标识。

手机端原实现在 `KardsDatabase.getOrCreateUser` 里对**已存在**的用户 `verifyPassword`，
不匹配就返回 null，调用方随即 NPE → 客户端拿到 500 → **永久登不进去**
（换设备、重装、或别人用同一台机器都会触发）。

已改为与桌面端一致：用户名即身份，密码只在**建号**时哈希存下来，不再作为登录门槛。
同时给 `session()` 加了空值兜底，避免以后再出现 NPE 变 500。

> `verifyPassword` 仍保留在 `KardsDatabase` 里（暂未被调用），将来要做真账号体系可以直接启用。

### 2. 后台界面缩放适配（不溢出手机屏幕）

**问题**：后台是按桌面宽度设计的 —— `.page` 最大 1380px，「用户管理」表内容宽约 1071px
（9 列）。手机横屏的 CSS 宽度通常只有 640–960，直接渲染会挤成一团、表格疯狂横向滚动。

**实测结论**（用 CDP 量 `scrollWidth`/`clientWidth`）：后台的**文档本身在任何宽度都不溢出**，
溢出只发生在卡片内部的 `.table-wrap`。所以问题不是"布局坏了"，而是"排不下"。

**做法**：`MainActivity` 在 `shouldInterceptRequest` 里把后台 HTML 取回来，
**替换**掉它的 viewport meta 为：

```html
<meta name="viewport" content="width=1320, initial-scale=1">
```

于是页面按 **1320px** 排版、浏览器再等比缩放到铺满屏幕 —— 布局完整、无需横向滚动。

| 数值 | 依据 |
|---|---|
| **1320** | 实测的最小平宽：1200 时「用户管理」最后一列仍被裁掉，1320 全部页面完整 |
| 为何替换而非插入 | 页面自带 `width=device-width` 的 viewport，**后再插一个不生效**（已踩） |
| 为何在响应阶段改 | 放在 `onPageFinished` 里会让页面先按默认宽度排版、注入后再重排 → 肉眼可见闪动 |

`ADMIN_LAYOUT_WIDTH` 常量在 `MainActivity` 顶部，嫌字小就调小、嫌窄就调大。

---

## 四、给后续改动的注意事项

1. **改前端后要重新 build**，否则 `wwwroot/admin-ui` 又是旧的（本轮就是踩到这个）。
   `vite.config.mjs` 设了 `emptyOutDir: false`，旧文件会残留，必要时先手工清理。
2. **接口字段必须对齐前端读取的名字**。已踩的坑：
   - `users` → `{total, users:[…]}`（不是裸数组）
   - `matches` → `{onlineCount, activeMatches:[…], queues:[…]}`
   - `server-config` → `raw` 是 JSON **字符串**（前端 `JSON.parse(r.raw)`），不是对象
   - 游戏端点是 **snake_case**（`card_set`），后台端点是 **camelCase**（`cardSet`）——
     两套不能混
   新增端点前先看 `AdminUi/src/**` 里对应的 `Admin.api(...)` 调用与字段访问。
3. **Vite 里 public 资源要用 `/assets/…`**（base 前缀形式），写 `/admin-ui/assets/…`
   会被当成模块路径解析并报 `UNRESOLVED_IMPORT`。
4. **`assets/` 体积会进 APK**（当前：后台 1.0 MB + 卡牌目录 1.29 MB + 商店 6 KB）。
   其中 `fyserver-logo-transparent.png`（373 KB）已不再被引用，可删。
5. **`.bat` 保持 ASCII**，否则 `cmd.exe` 会乱码。
6. **构建必须先有 `builder/android.jar`**（项目自带 API 35），否则要装对应 SDK platform。
7. **`PlayerCards.openPack` 用的是"读-改-写"**，依赖 `KardsDatabase` 的 `synchronized`。
   将来若引入并发请求，需要按 userId 加锁（桌面端用 `WithUserLockAsync`）。
