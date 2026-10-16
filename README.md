# 智慧图书借阅管理系统

基于 Spring Boot 3 + Vue 3 + 微信小程序的图书馆借阅管理系统，内置 AI 阅读助手（LangChain4j + RAG 知识库）。

## 功能特性

- 📚 图书管理：分类树、检索、馆藏与库存管理
- 🔄 借阅流程：申请 → 取书码 → 归还确认，全流程状态机
- 👥 三类角色：读者（教师/学生）、管理员，JWT 认证
- 🤖 AI 助手：流式对话（SSE）、Function-Tools 查询业务数据、RAG 知识库增强（本地 ONNX embedding，零 API 成本）
- 📱 三端覆盖：Web 管理端 + Web 读者端、微信小程序读者端

## 技术栈

| 端 | 技术 |
|---|---|
| 后端 | Java 22 · Spring Boot 3.3 · MyBatis · MySQL 8 · Flyway · LangChain4j |
| Web 前端 | Vue 3 · Vite · Element Plus · Pinia · ECharts |
| 小程序 | 微信原生小程序（自定义 tabBar） |
| 数据工具 | Python 爬虫/数据修补脚本（`spider/`） |

## 快速开始

### 环境要求

- JDK 22、Maven 3.9+
- Node.js 20+、pnpm
- MySQL 8.0
- 微信开发者工具（仅小程序端）

### 1. 后端

```bash
cd backend
# 数据库连接、JWT secret 等通过环境变量或 application-local.yml 配置
mvn spring-boot:run
```

首次启动 Flyway 会自动建库表并写入种子数据，无需手动执行 SQL。

### 2. Web 前端

```bash
cd frontend
pnpm install
pnpm dev
```

### 3. 微信小程序

用微信开发者工具导入 `miniprogram-reader/` 目录，在 `project.config.json` 中配置自己的 appid。

## 项目结构

```
├── backend/           # Spring Boot 后端（controller/service/mapper/entity/db migration）
├── frontend/          # Vue3 管理端 + 读者端 Web
├── miniprogram-reader/# 微信小程序读者端
├── rag-docs/          # RAG 知识库文档（馆规/借阅规则等）
├── spider/            # 图书数据爬虫与导入脚本
└── docs/              # 设计文档
```

## 参与贡献

请阅读 [CONTRIBUTING.md](./CONTRIBUTING.md)（提交信息规范、分支与发版流程）。

## 许可证

[MIT](./LICENSE)
