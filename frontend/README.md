<div align="center">

# 图书借阅系统 · Web 端

Vue 3 管理端 + 读者端单页应用

![Vue 3](https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white) ![Vite](https://img.shields.io/badge/Vite-8-646CFF?logo=vite&logoColor=white) ![Element Plus](https://img.shields.io/badge/Element%20Plus-2.14-409EFF?logo=element&logoColor=white) ![Pinia](https://img.shields.io/badge/Pinia-4-FFD859?logo=pinia&logoColor=black) ![ECharts](https://img.shields.io/badge/ECharts-6-AA344D?logo=apacheecharts&logoColor=white) ![pnpm](https://img.shields.io/badge/pnpm-ready-F69220?logo=pnpm&logoColor=white)

[← 返回总览](../README.md)

</div>

> [!IMPORTANT]
> 本项目为毕业设计与学科竞赛作品，仅供学习交流，未经生产环境验证。

## 功能页面

| 路径 | 页面 | 功能 |
|------|------|------|
| `/login` | 登录 | 账号密码登录，JWT 鉴权 |
| `/dashboard` | 仪表盘 | 统计卡片、借阅趋势、分类藏书分布 |
| `/profile` | 个人中心 | 个人信息维护、密码修改 |
| `/book/list` | 图书管理 | 检索、增删改查、封面管理、馆藏详情 |
| `/book/category` | 分类管理 | 可搜索分类树、启用状态、树形展开 |
| `/borrow/list` | 借阅管理 | 申请/取书/归还/确认状态机、取书码 |
| `/borrow/fine` | 罚款记录 | 逾期罚款结算与查看 |
| `/user/list` | 用户管理 | 读者（教师/学生）与管理员管理 |
| `/ai/chat` | AI 助手 | SSE 流式对话、工具调用卡片、授权卡、锚点导航 |
| `/ai/trace` | Agent 运行日志 | 工具调用序列、门禁触发与 token 消耗复盘 |
| `/announce/list` | 公告管理 | 公告发布与通知 |
| `/knowledge/list` | 知识库 | RAG 文档上传、启用/停用、分段管理与检索测试 |
| `/system/settings` | 系统设置 | AI 多渠道管理、借阅规则、人设在线编辑、邮件配置 |

## 快速开始

### 环境要求

- Node.js 20+
- pnpm
- 运行中的后端服务（默认 `http://127.0.0.1:8090`，见 [backend/README.md](../backend/README.md)）

### 安装与运行

```bash
pnpm install
pnpm dev    # http://localhost:5173，已配置 0.0.0.0 可局域网访问
```

### API 代理

开发与预览模式下 `/api` 请求由 Vite 代理转发到 `http://127.0.0.1:8090`（见 `vite.config.js`）。后端地址变化时同步修改 `server.proxy` 与 `preview.proxy` 两处 `target` 即可，前端代码无需改动；也可用环境变量 `VITE_BACKEND_TARGET` 覆盖（需写在 `frontend/.env.local`，并以 `VITE_` 前缀注入）。

### 构建与预览

```bash
pnpm build     # 产物输出至 dist/
pnpm preview   # 本地预览构建产物（含 /api 代理）
```

## 目录结构

```text
frontend/
├── vite.config.js         # 别名 @ → src；dev/preview 的 /api 代理
└── src/
    ├── api/               # 接口封装（request.js 为 axios 实例与拦截器）
    ├── views/             # 页面（ai / announcement / book / borrow / dashboard /
    │                      #   error / knowledge / login / profile / system / user）
    ├── components/        # 公共组件与 AI 组件（AiChatPanel、AiGenUi、AiFloatingBall 等）
    ├── composables/       # 组合式函数（如滚动位置恢复）
    ├── layout/ styles/ assets/
    ├── router/            # 路由与登录守卫（含 403/404 拦截）
    ├── stores/            # Pinia 状态
    └── utils/             # 工具函数
```
