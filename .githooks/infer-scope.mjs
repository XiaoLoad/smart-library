// 根据暂存文件推断主要 scope，写入提交信息草稿首行占位
// 用法: node infer-scope.mjs <commit-msg-file> [git-dir]
import { readFileSync, writeFileSync } from 'node:fs'
import { execSync } from 'node:child_process'
import path from 'node:path'

const [msgFile, gitDir = '.git'] = process.argv.slice(2)
const repoRoot = path.resolve(gitDir, '..')

// 与 commit-msg.mjs 保持一致的映射
const PATH_SCOPES = [
  [/^\.github\/workflows\//, 'ci'],
  [/^backend\/src\/main\/resources\/db\//, 'db'],
  [/^backend\//, 'backend'],
  [/^frontend\//, 'frontend'],
  [/^miniprogram\//, 'miniprogram'],
  [/^(spider|rag-docs)\//, 'spider'],
  [/^(docs|.*\.md)$/, 'docs'],
]

let staged = []
try {
  staged = execSync('git diff --cached --name-only', { cwd: repoRoot, encoding: 'utf8' })
    .trim().split('\n').filter(Boolean)
} catch { process.exit(0) }
if (!staged.length) process.exit(0)

const counts = {}
for (const f of staged) {
  const hit = PATH_SCOPES.find(([re]) => re.test(f.replace(/\\/g, '/')))
  const s = hit ? hit[1] : 'chore'
  counts[s] = (counts[s] || 0) + 1
}
const mainScope = Object.entries(counts).sort((a, b) => b[1] - a[1])[0][0]
const others = Object.keys(counts).filter(s => s !== mainScope)

let msg = readFileSync(msgFile, 'utf8')
if (msg.includes('# >') || !/^(#|$)/.test(msg.trimStart().charAt(0) === '#' ? msg : msg)) {
  // 已有占位则不重复
}
if (/# >\(type\)/.test(msg)) process.exit(0)

const suggestion = others.length
  ? `${mainScope}（次要端: ${others.join(', ')}，请在 body 说明）`
  : mainScope

msg = `# >请填写: type(${suggestion}): subject\n` + msg
writeFileSync(msgFile, msg)
