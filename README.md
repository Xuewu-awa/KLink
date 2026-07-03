# KLink — KARDS 本地联机工具

将 KARDS 游戏服务器嵌入 APK，一个安装包即可**本地开服 / 局域网联机 / 连接远程服**。

---

## 运行模式

| 模式 | 说明 |
|------|------|
| **本地模式** | 手机自建服务器 (`127.0.0.1:5231`)，单人自娱自乐 + 人机对战 |
| **局域网模式** | 服务器绑定 `0.0.0.0`，UDP 广播房间信息，好友扫码加入 |
| **远程转发** | TCP 透明代理，本地 `127.0.0.1:5231` → 远程服务器，HTTP + WebSocket 全协议转发 |

---

## 项目结构

```
source/
├── src/com/xuewu/KLink/              # 壳应用层
│   ├── MainActivity.java             WebView 容器，夺舍原游戏入口
│   ├── KLinkBridge.java              JS ↔ Java 全功能桥接
│   ├── ProxyServer.java              TCP 透明代理
│   ├── LanDiscovery.java             UDP 广播 + 局域网扫描
│   ├── ModManager.java               PAK 模组管理
│   └── ServerService.java            前台服务保活
│
├── src/com/android1939/kardsserver/  # 游戏服务器核心
│   ├── KardsLocalServer.java         服务器入口
│   ├── KardsHttpHandler.java         HTTP 路由（30+ 游戏 API）
│   ├── MatchManager.java             匹配队列 + Bot AI
│   ├── SimpleHttpServer.java         HTTP/1.1 服务器（纯 Socket）
│   ├── SimpleWebSocketServer.java    WebSocket 服务器
│   ├── KardsDatabase.java            SQLite 持久化
│   ├── AssetStore.java               游戏数据加载
│   ├── DeckCodeManager.java          卡组代码解析
│   ├── util/                         JWT / 加密 / 时间工具
│   └── ...
│
├── assets/                            # 前端控制面板
│   ├── index.html                    主界面（侧边导航布局）
│   ├── css/style.css                 设计系统（暗色主题）
│   ├── js/
│   │   ├── app.js                    前端逻辑 & 桥接
│   │   └── theme.js                  主题引擎（自定义/导入/导出）
│   └── kards-server/                 游戏静态数据（卡牌库等）
│
└── res/xml/
    └── network_security_config.xml   允许明文 HTTP
```

---

## 功能

- **服务器生命周期** — 启动 / 停止 / 模式切换 / 实时状态轮询
- **人机对战** — 训练模式自动创建 Bot 对局，Bot 自动出牌
- **局域网发现** — UDP 广播（端口 5233）+ 主动扫描 + 一键加入
- **模组管理** — PAK 文件扫描 / 安装 / 卸载 / 启用禁用
- **远程代理** — HTTP + WebSocket 全协议双向 TCP 转发
- **配置持久化** — SharedPreferences + localStorage 双层存储
- **高度可自定义主题** — 14 色配色 / 背景图片/渐变 / 间距/圆角 / 噪点纹理 / 5 套内置预设 / 导出导入 JSON

---

## 技术架构

```
┌──────────────────────────────────────────────┐
│  Android WebView                              │
│  index.html + style.css + app.js + theme.js   │
│  window.KLink ─── JavaScript Bridge           │
├──────────────────────────────────────────────┤
│  KLinkBridge.java                             │
│  服务器控制 / 房间发现 / 模组 / 主题导入导出     │
├──────────────────────────────────────────────┤
│  kardsserver                                  │
│  HTTP + WebSocket + MatchMaker + SQLite       │
└──────────────────────────────────────────────┘
```

游戏客户端 → `127.0.0.1:5231` (HTTP) / `127.0.0.1:5232` (WebSocket)

---

## 主题系统

侧边栏「主题」面板支持实时自定义：

- **14 个颜色变量** — 背景 5 层、文字 4 级、强调色、语义色，圆形取色器实时预览
- **背景** — 纯色 / CSS 渐变 / 图片（系统选择器 + 自动压缩）
- **排版** — 紧凑 / 标准 / 宽松三档间距 + 锐利 / 标准 / 圆润三档圆角
- **噪点纹理** — 开关 + 强度调节
- **5 套内置预设** — Deep Dark / Moon Light / Forest / Sunset / Ocean
- **导出/导入** — JSON 文件保存/分享/导入，一键恢复默认

所有设置自动保存到 `localStorage`，重启不丢失。

---

## 相关项目

本项目 Java 版服务器核心逻辑移植自 Go 语言实现：

👉 [kardswalker/kards-server-go](https://github.com/kardswalker/kards-server-go)

---

## 作者

**bilibili@氧化铜依旧CuO**

特别感谢：**阿伟**

---

## 免责声明

本项目仅供学习交流使用。

- 修改、分发 KARDS APK 可能违反 1939 Games 的服务条款。请仅用于个人研究，勿公开发布修改后的安装包。
- 请勿将本工具用于线上官服作弊。
- 内嵌 SQLite 数据库存储的账号信息在本地，不涉及任何云端同步或隐私收集。

使用本项目即表示你已阅读并同意以上条款。
