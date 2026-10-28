<div align="center">

# 图书借阅系统 · 后端服务

Spring Boot 3 单体后端，内置 Flyway 自动建库与 AI 能力（多渠道 LLM + RAG + 工具链）

![Java](https://img.shields.io/badge/Java-22-EA2D2E?logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white) ![MyBatis](https://img.shields.io/badge/MyBatis-3-000000?logo=mybatis&logoColor=white) ![MySQL](https://img.shields.io/badge/MySQL-8-4479A1?logo=mysql&logoColor=white) ![Flyway](https://img.shields.io/badge/Flyway-迁移-CC0200?logo=flyway&logoColor=white) ![LangChain4j](https://img.shields.io/badge/LangChain4j-RAG-7C4DFF)

[← 返回总览](../README.md)

</div>

> [!IMPORTANT]
> 本项目为毕业设计作品，仅供学习交流，未经生产环境验证。JWT secret、微信 secret 等敏感配置请勿用于真实环境。

## 快速开始

### 环境要求

- JDK 22、Maven 3.9+
- MySQL 8（库不存在时自动创建，建议服务端默认字符集 `utf8mb4`）

### 配置项

核心配置位于 `src/main/resources/application.yml`：

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `server.port` / `context-path` | `8090` / `/api` | 所有接口以 `/api` 为前缀；端口可用 `SERVER_PORT` 覆盖 |
| `spring.datasource.*` | `localhost:3306/library_db` | `createDatabaseIfNotExist` 自动建库 |
| `spring.flyway.*` | 启用 | 首次启动自动执行 V1~V8 建库表 + 幂等种子数据 |
| `app.upload-dir` | `./uploads` | 封面/头像等静态资源目录 |
| `jwt.secret` / `jwt.expire-ms` | 见配置文件 | 生产请用环境变量注入新 secret |
| `wechat.appid` / `wechat.secret` | 见配置文件 | 小程序 `code2session` 登录凭据 |
| `spring.profiles.active` | `dev` | dev 会输出 mapper SQL 调试日志 |

AI 渠道（LLM key、人设、知识库等）不写死在配置文件，由**系统设置页（Web 端 `/system/settings`）在线管理**，存于数据库。

### 运行

```bash
mvn spring-boot:run
# 或
mvn package -DskipTests && java -jar target/*.jar
```

启动后 Flyway 自动完成建库建表与种子数据，无需手动执行任何 SQL。

## 架构分层

```text
backend/
├── src/main/java/com/library/
│   ├── controller/    # REST 接口（auth/book/borrow/fine/user/category/
│   │                  #   dashboard/announcement/notification/system-setting/
│   │                  #   ai/rag/wechat-auth/file）
│   ├── service/       # 业务逻辑（AI 会话编排在此层）
│   ├── mapper/        # MyBatis 接口
│   ├── entity/        # 实体
│   └── config/        # 安全、MyBatis、Web、LangChain4j 等配置
└── src/main/resources/
    ├── db/migration/  # Flyway 迁移（V1~V8，见下方规则）
    └── mapper/        # MyBatis XML
```

## AI 模块

- **多渠道管理**：LLM 渠道在系统设置页增删改，默认渠道由 AI 助手与 RAG 共用；
- **RAG 知识库**：Web 端上传文档 → 服务端分段与向量化，AI 对话时检索增强；
- **工具链**：AI 助手可调用系统工具（如查询借阅状态、图书检索、获取借阅规则），流式输出经 SSE 推送至前端。

## Flyway 迁移规则

- `V1~V8` 已发布，**禁止修改**（checksum 校验会失败）；
- 新的表结构/数据变更请新增 `V{n}__描述.sql`，文件名递增、内容幂等优先；
- 已有库但无迁移记录的场景由 `baseline-on-migrate` 自动处理。
