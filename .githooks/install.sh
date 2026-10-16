#!/bin/sh
# 安装 git 钩子：将 core.hooksPath 指向本目录
# 用法: sh .githooks/install.sh
cd "$(git rev-parse --show-toplevel)" || exit 1
git config core.hooksPath .githooks
echo "✓ git hooks 已启用（.githooks/）：提交信息校验 + scope 自动推断"
echo "  临时跳过校验: SKIP_COMMIT_CHECK=1 git commit ..."
