<div align="center">

# 图书借阅系统 · 微信小程序读者端

微信原生小程序，含书库、借阅、AI 助手、消息与个人中心

![WeChat](https://img.shields.io/badge/微信小程序-原生-07C160?logo=wechat&logoColor=white) ![自定义 tabBar](https://img.shields.io/badge/tabBar-自定义-3A5A6B) ![分包加载](https://img.shields.io/badge/主包-已分包瘦身-409EFF) ![骨架屏](https://img.shields.io/badge/列表-骨架屏-FFD859)

[← 返回总览](../README.md)

</div>

> [!IMPORTANT]
> 本项目为毕业设计作品，仅供学习交流，未经生产环境验证。

## 功能页面

| 页面 | 功能 |
|------|------|
| 书库（tab） | 三列封面书架、抽屉式分类树、检索、骨架屏与列表缓存 |
| 图书详情 | 藏书票式布局、封面多通道渲染、借阅申请 |
| 借阅（tab） | 借阅/待取书/归还中/已完成分段左右滑动切换、额度卡 |
| 取书码（分包） | 取书二维码展示，随 `packageExtra` 分包加载 |
| AI 助手（tab） | SSE 流式对话、工具链徽章、知识库参考资料、时间记录与目录定位 |
| 消息（tab） | 通知/公告 swiper 分段、吸顶坞、未读角标 |
| 我的（tab） | 个人资料、借阅额度、密码修改、罚款记录 |

## 快速开始

### 环境要求

- 微信开发者工具（最新稳定版）
- 运行中的后端服务（见 [backend/README.md](../backend/README.md)），小程序与开发机需处于同一局域网

### 运行步骤

1. 用微信开发者工具**导入本目录**（`miniprogram-reader/`）；
2. 在 `project.config.json` 中填入自己的 `appid`（或使用测试号）；
3. 修改 `utils/core/config.js`：

   ```js
   const IS_DEV = true
   const DEV_ORIGIN = 'http://<你电脑的局域网IP>:8080'
   ```

4. 开发者工具勾选「详情 → 本地设置 → **不校验合法域名**」；
5. 编译运行。

### 域名与封面加载说明

- **接口请求**：开发期靠「不校验合法域名」放行 http；正式发布必须 HTTPS + 备案域名，并在小程序后台「开发管理 → 服务器域名」登记 `PROD_ORIGIN`。
- **封面加载**：真机上 `<image>` 加载 http 资源会被微信客户端拦截（上述开关不生效）。本项目实现了封面多通道渲染方案（`utils/cover.js`）：
  - 书库网格走 CSS 背景图直连（WebView 渲染，真机 http 可显示）；
  - 详情封面/头像走 base64 通道（`COVER_MODE = 'base64'`，经 `/file/image-base64` 转数据 URI 回填）。

## 目录结构

```text
miniprogram-reader/
├── app.js / app.json      # 全局逻辑；自定义 tabBar；lazyCodeLoading
├── pages/                 # 主包页面（书库/借阅/消息/我的/AI 助手等）
├── packageExtra/          # 分包（取书码页 + qrcode 依赖，主包瘦身约 55KB）
├── components/            # 公共组件
├── utils/
│   ├── core/config.js     # 环境配置（ORIGIN、封面模式）
│   ├── cover.js           # 封面多通道渲染
│   └── ...                # stale-while-revalidate 缓存、统一错误上报等
└── custom-tab-bar/        # 自定义底部导航
```
