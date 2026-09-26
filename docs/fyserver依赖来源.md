# fyserver 依赖来源与重建说明

> 手机端**不运行** fyserver —— APK 里没有任何 .NET 二进制，服务端是**移植成 Java** 的。
> fyserver 对手机端只提供两样东西：**行为契约**（接口/字段/算法照着写）和
> **前端资产**（`assets/admin-ui/` 由它的 `AdminUi/src` 构建而来）。
>
> 所以这是一条**源码级、构建期**的依赖，不是运行期依赖。

---

## 一、固定来源

| 项 | 值 |
|---|---|
| 上游仓库 | `https://github.com/CCB-TEAM/fyserver.git` |
| 基线提交 | **`62d8d4c`**（分支 `fyserver`，作者提交信息 *Move new player card defaults to a dedicated page*，2026-09-24） |
| 上游 `master` 当时位置 | `e9d9577`（比基线新 5 个提交） |
| 本地工作树 | `klink-dotnet/tem/fyserver`（**普通嵌套克隆，不是 submodule**） |
| 本地状态 | 该提交**不在上游任何分支**上 + 117 个未提交改动 —— 即这是一个**本地派生状态** |

> ⚠️ 单看 `62d8d4c` **不足以复现**手机端的后台资产：它还叠加了 KLink 的本地改动。
> 完整快照见下节。

## 二、改动快照（patch）

| Patch | 位置 | 内容 |
|---|---|---|
| `fyserver-klink-branding.patch` | 本目录 | 品牌由 FYServer 改为 KLink（7 个前端源文件），**可独立重施** |
| `fyserver-adminui-full.patch` | `klink-dotnet/docs/fyserver-patches/` | AdminUi 全部本地改动（41 文件，含二进制）—— **手机端后台资产的真实来源** |
| `fyserver-server-full.patch` | `klink-dotnet/docs/fyserver-patches/` | 服务端其余改动（63 文件，含 `Program.cs` 的 `--no-console`/优雅退出、`LauncherEndpoints` 等） |

三个 patch 都验证过 `git apply --check --reverse` 通过，即与当前工作区一致、可重新应用。

**重施方法**（在 `tem/fyserver` 里，基线 `62d8d4c`）：

```bash
git checkout 62d8d4c
git apply /path/to/fyserver-adminui-full.patch
git apply /path/to/fyserver-server-full.patch
# 若只想改品牌（在已有基线上）：
git apply /path/to/fyserver-klink-branding.patch
```

> ⚠️ 生成 patch 时必须带 `--binary`，否则含 `fyserver-logo-*.png` 的改动会因为
> `cannot apply binary patch ... without full index line` 而失败。

## 三、重建手机端的后台资产

`source/assets/admin-ui/` 不是手工维护的，必须由 fyserver 的 `AdminUi/src` 构建：

```bash
cd klink-dotnet/tem/fyserver/AdminUi
./node_modules/.bin/vite.cmd build        # 输出到 ../wwwroot/admin-ui
```

然后把 `wwwroot/admin-ui/` 的内容整体复制到 `source/assets/admin-ui/`（59 文件 / 约 1.0 MB）。

构建配置见 `AdminUi/vite.config.mjs`（`base: '/admin-ui/'`、`outDir: '../wwwroot/admin-ui'`、
`emptyOutDir: false`）。

### 三个必踩的坑

1. **改完前端一定要重新 build**。`wwwroot/admin-ui/` 不会因为源码变化自动更新 ——
   曾经出现"改了页面但 WebView 里没变"就是因为拿的是旧产物。
2. **`emptyOutDir: false`** 意味着旧文件会残留，必要时先手工清理输出目录。
3. **Vite 里 public 资源要写 `/assets/…`**（base 前缀形式）。写 `/admin-ui/assets/…`
   会被当成模块路径解析并报 `UNRESOLVED_IMPORT` —— 品牌改成 KLink 时踩过。

## 四、手机端本地对 fyserver 的对应关系

后台接口按 fyserver 的 `/admin/api/*` 契约逐条移植，实现见：

| 手机端文件 | 对应 fyserver |
|---|---|
| `http/AdminApiHandler.java` | `Endpoints/AdminApiEndpoints.cs` + `Middleware/AdminApiAuthorizationMiddleware.cs` |
| `http/AdminUiHandler.java` | `Program.cs` 的静态文件中间件 + `Middleware/AdminUiEntryMiddleware.cs` |
| `ServerOptionsStore.java` | `Services/ClientServerConfigService.cs` |
| `CardCatalog.java` | `Services/CardCatalogService.cs` |
| `PlayerCards.java` | `Services/PlayerCardService.cs` |
| `StoreService.java` | `Services/StoreConfigService.cs` + `PlayerEndpoints` 的购买结算 |

游戏侧数据文件同样来自 fyserver：

- `assets/kards-server/cards-from-fmodel.json` ← fyserver 仓库根目录同名文件（2067 张）
- `assets/kards-server/store.json` ← fyserver `config/store.json`

## 五、与桌面端的关系

桌面端 `klink-dotnet` 是**子进程托管** fyserver 的真实二进制；手机端是把同样的行为
**移植成 Java**。两边共用同一份后台前端资产与同一套接口契约，但：
**桌面端升级 fyserver 基线后，手机端需要按上表逐条比对补差异**，
不能假定自动同步。

当前手机端未移植的部分见 `fyserver后台对齐-进度.md` 的待办章节（约 45 条端点）。
