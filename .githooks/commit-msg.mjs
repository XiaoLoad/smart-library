// Conventional Commits 提交信息校验：格式 + scope 与实际改动一致性
// 用法: node commit-msg.mjs <msg-file> [git-dir]
import { readFileSync, existsSync } from 'node:fs'
import { execSync } from 'node:child_process'
import path from 'node:path'

const [msgFile, gitDir = '.git'] = process.argv.slice(2)
const repoRoot = path.resolve(gitDir, '..')

const TYPES = ['feat', 'fix', 'perf', 'refactor', 'docs', 'test', 'build', 'ci', 'chore', 'revert']
const SCOPES = ['backend', 'frontend', 'miniprogram', 'db', 'ai', 'rag', 'spider', 'docs', 'ci']

// 路径 → scope 映射（顺序即优先级，先匹配者胜）
const PATH_SCOPES = [
  [/^\.github\/workflows\//, 'ci'],
  [/^backend\/src\/main\/resources\/db\//, 'db'],
  [/^backend\//, 'backend'],
  [/^frontend\//, 'frontend'],
  [/^miniprogram\//, 'miniprogram'],
  [/^(spider|rag-docs)\//, 'spider'],
  [/^(docs|.*\.md)$/, 'docs'],
]

const header = readFileSync(msgFile, 'utf8').split(/\r?\n/)[0]
const re = new RegExp(`^(${TYPES.join('|')})(\\((${SCOPES.join('|')})\\))?(!)?: (.+)$`)
const m = header.match(re)

if (!m) {
  console.error(`
✗ 提交信息不符合 Conventional Commits 规范: ${header}
  格式: <type>(<scope>): <subject>
  type: ${TYPES.join(' | ')}
  scope: ${SCOPES.join(' | ')}（可省略）
  示例: feat(miniprogram): 借阅页支持按状态筛选
  跳过校验: SKIP_COMMIT_CHECK=1 git commit ...`)
  process.exit(1)
}

const [, type, , scope, bang, subject] = m

if (subject.length > 60) {
  console.error(`✗ subject 过长（${subject.length} > 60 字符）: ${subject}`)
  process.exit(1)
}

// ---- scope 一致性校验：比对暂存文件推断出的 scope 集合 ----
let staged = []
try {
  staged = execSync('git diff --cached --name-only', { cwd: repoRoot, encoding: 'utf8' })
    .trim().split('\n').filter(Boolean)
} catch { /* 非 git 环境则跳过 */ }

if (staged.length) {
  const inferred = new Set()
  for (const f of staged) {
    const hit = PATH_SCOPES.find(([re2]) => re2.test(f.replace(/\\/g, '/')))
    inferred.add(hit ? hit[1] : 'chore')
  }
  // 无 scope 时不比对；跨端提交若 scope 为推断集合之一即放行
  if (scope && !inferred.has(scope)) {
    console.error(`
✗ scope 与实际改动不一致:
  提交信息 scope: ${scope}
  暂存文件推断:   ${[...inferred].join(', ')}
  规则: 一个 commit 使用主要变更端的 scope，次要端在 body 中说明`)
    process.exit(1)
  }
}

// BREAKING CHANGE 提示（不阻断）
const body = readFileSync(msgFile, 'utf8')
if (bang && !/BREAKING CHANGE/i.test(body)) {
  console.warn('⚠ 使用了 "!"，建议在 body/footer 中补充 "BREAKING CHANGE: <迁移说明>"')
}
