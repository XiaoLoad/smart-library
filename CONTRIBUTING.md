# 贡献指南

感谢参与贡献！请遵循以下规范，所有约束均由本地 git 钩子与 CI 自动校验。

## 环境准备（一次性）

```bash
sh .githooks/install.sh
```

该命令启用两个本地钩子：

- **prepare-commit-msg**：`git commit`（无 `-m`）时根据暂存文件自动推断端 scope，写入草稿首行；
- **commit-msg**：校验提交信息格式，并比对 scope 与实际改动文件是否一致。

临时跳过校验：`SKIP_COMMIT_CHECK=1 git commit ...`（仅限极特殊情况）。

## 提交信息规范（Conventional Commits 1.0.0）

```
<type>(<scope>): <subject>

[body]
[footer]
```

- **type**：`feat` `fix` `perf` `refactor` `docs` `test` `build` `ci` `chore` `revert`
- **scope**（固定枚举）：`backend` `frontend` `miniprogram` `db` `ai` `rag` `spider` `docs` `ci`
  - 一个 commit 跨多端时取**主要变更端**，次要端在 body 中说明
  - `db`（Flyway 迁移）优先于 `backend`；AI/RAG 后端改动用 `ai`/`rag`
- **subject**：中文动宾结构，≤60 字符，结尾不加句号
- **破坏性变更**：type 后加 `!`，并在 footer 写 `BREAKING CHANGE: <迁移说明>`

示例：

```
feat(miniprogram): 借阅页支持按状态筛选

fix(backend): 修复归还确认后可用库存未回增的问题
  Closes #42

feat(rag)!: 知识库分段结构变更，需执行 V9 迁移

BREAKING CHANGE: rag_segments 表结构变更，旧数据需重建索引
```

## 分支与 PR

| 分支 | 用途 |
|---|---|
| `main` | 稳定分支，仅接受 PR（Squash Merge），tag 只打在 main |
| `feat/xxx` `fix/xxx` `docs/xxx` | 短期开发分支，合并即删 |
| `hotfix/vX.Y.Z` | 从 tag 切出的紧急修复，需同时合回 main |

PR 要求：

1. **PR 标题 = 最终 commit 信息**（Squash Merge 时使用），必须符合 Conventional Commits（CI 校验）；
2. CI 全绿（按改动路径自动运行对应 job：`backend/**`→后端构建、`frontend/**`→前端构建、`miniprogram/**`→语法检查）；
3. 不要提交 `backend/uploads/`、`spider/data/`、`*.pyc` 等运行时/生成数据（卫生检查会拒绝）。

## 数据库变更

- 只能新增 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V{N}__desc.sql`，**禁止修改已发布的迁移**；
- 涉及 schema 不兼容变更时提交信息必须带 `!` 与 BREAKING CHANGE 说明。

## 版本与发版

版本号严格遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)：

| 变更 | 版本递增 |
|---|---|
| BREAKING CHANGE | MAJOR（0.x 阶段为 MINOR） |
| `feat` | MINOR |
| `fix`/`perf` | PATCH |
| 仅 `docs`/`chore`/`ci` 等 | 不发版 |

发版流程：

1. 维护者确认 main CI 全绿；
2. 更新 `VERSION`、`backend/pom.xml`、`frontend/package.json` 三处版本一致；
3. 提交 `chore(release): vX.Y.Z` 并打 tag：`git tag vX.Y.Z && git push origin main --tags`；
4. GitHub Actions 自动完成：版本一致性校验 → 三端构建 → git-cliff 生成变更日志 → 创建 GitHub Release（附 jar/dist 产物与校验和）。

预发布版本使用 `-alpha.N` / `-beta.N` / `-rc.N` 标识（如 `v1.0.0-rc.1`），Release 自动标记为预发布。
