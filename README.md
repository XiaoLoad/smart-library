<div align="center">

<img src="docs/images/logo.svg" alt="智慧图书借阅管理系统" width="96" />

# 智慧图书借阅管理系统

**一个面向毕业设计的全栈实践项目：Spring Boot 3 + Vue 3 + 微信小程序，内置 AI 阅读助手。**

基于 Spring Boot 3 + Vue 3 + 微信小程序的图书馆借阅管理系统，内置 AI 阅读助手（LangChain4j + RAG 知识库）。

![Version](https://img.shields.io/github/v/tag/your-username/smart-library?sort=semver&label=version) ![License](https://img.shields.io/badge/license-MIT-green) ![Java](https://img.shields.io/badge/Java-22-007396?logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white) ![MyBatis](https://img.shields.io/badge/MyBatis-3.x-000000?logo=mybatis&logoColor=white) ![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white) ![Flyway](https://img.shields.io/badge/Flyway-migration-CC0200?logo=flyway&logoColor=white) ![LangChain4j](https://img.shields.io/badge/LangChain4j-1.20-E25A1C?logoColor=white) ![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white) ![Vite](https://img.shields.io/badge/Vite-latest-646CFF?logo=vite&logoColor=white) ![Element Plus](https://img.shields.io/badge/Element%20Plus-2.x-409EFF?logo=element&logoColor=white) ![Pinia](https://img.shields.io/badge/Pinia-latest-FFD859?logo=pinia&logoColor=black) ![ECharts](https://img.shields.io/badge/ECharts-5-AA344D?logo=apacheecharts&logoColor=white) ![WeChat](https://img.shields.io/badge/微信小程序-原生-07C160?logo=wechat&logoColor=white) ![pnpm](https://img.shields.io/badge/pnpm-ready-F69220?logo=pnpm&logoColor=white)

[快速开始](#快速开始) · [功能特性](#功能特性) · [项目结构](#项目结构) · [参与贡献](#参与贡献)

> [!IMPORTANT]
> **本项目为毕业设计作品，仅供学习交流，未经过生产环境验证。**
> 功能以演示完整度为目标，安全加固、容错与性能均未按商业标准打磨，请勿直接用于真实图书馆业务。
> 使用前请备份数据；如遇到问题，欢迎到 [Issues](../../issues) 提交反馈。
> 首次启动后端时 Flyway 会自动建库表并写入种子数据，无需手动执行 SQL。

</div>

---

## 功能特性

- 📚 **图书管理** —— 分类树、检索、馆藏与库存管理，封面多通道渲染。
- 🔄 **借阅流程** —— 申请 → 取书码 → 归还确认，全流程状态机，两步确认制归还并自动结算逾期罚款。
- 🤖 **AI 助手** —— 流式对话（SSE）、Function-Tools 查询业务数据（5→16 个按角色拆分工具）、RAG 知识库增强（本地 ONNX embedding，零 API 成本）、深度思考可开关。
- 🔔 **催还通知** —— 站内信 + 邮件异步投递，管理后台可管理通知。
- 📊 **数据可视化** —— 管理员仪表盘统计、分类藏书分布、借阅趋势。
- 📱 **三端覆盖** —— Web 管理端 + Web 读者端、微信小程序读者端（自定义 tabBar、骨架屏、分包优化）。

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

### 2. Web 前端

```bash
cd frontend
pnpm install
pnpm dev
```

### 3. 微信小程序

用微信开发者工具导入 `miniprogram-reader/` 目录，在 `project.config.json` 中配置自己的 appid。

## 项目结构

```text
smart-library/
├── backend/            # Spring Boot 后端
│   └── src/main/resources/db/migration/   # Flyway 迁移脚本（自动建库）
├── frontend/           # Vue 3 管理端 + 读者端 Web
│   └── src/views/      #   页面（仪表盘、图书、借阅、AI 助手、系统设置等）
├── miniprogram-reader/ # 微信小程序读者端（书库/借阅/消息/AI 助手）
├── rag-docs/           # RAG 知识库文档（馆规/借阅规则等）
├── .githooks/          # 提交规范钩子（自动 scope 推断 + 校验）
├── .github/            # CI/CD 工作流、模板、git-cliff 配置
└── docs/               # 设计文档
```

## 技术栈

| 端 | 技术 |
|---|---|
| 后端 | Java 22 · Spring Boot 3.3 · MyBatis · MySQL 8 · Flyway · LangChain4j |
| Web 前端 | Vue 3 · Vite · Element Plus · Pinia · ECharts |
| 小程序 | 微信原生小程序（自定义 tabBar） |

## 参与贡献

```bash
sh .githooks/install.sh    # 启用提交规范钩子（首次克隆后执行一次）
```

提交信息遵循约定式提交（`feat` / `fix` / `perf` / `refactor` / `docs` / `test` / `build` / `ci` / `chore` / `revert`），scope 固定枚举并由钩子自动推断，CHANGELOG 由 [git-cliff](https://github.com/orhun/git-cliff) 在发版时自动生成。详见 [CONTRIBUTING.md](./CONTRIBUTING.md)。

## 更新日志

见 [Releases](../../releases)（tag 触发 CI 自动构建三端产物并生成变更日志）。

## License

[MIT](./LICENSE)
