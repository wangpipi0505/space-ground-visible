/** 比较规范/注释整理前后的前端执行内容，不用格式统计代替实际构建与测试。
 * TS以已锁定编译器removeComments实际转译；Vue/CSS仅行尾/尾空白变化，直接核对规范文本。
 */
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const { createRequire } = require('node:module')
const root = path.resolve(__dirname, '../..')
const localRequire = createRequire(path.join(root, 'frontend/package.json'))
const ts = localRequire('typescript')
const before = path.join(root, 'verification/runtime/T29/before-source')
const files = JSON.parse(fs.readFileSync(path.join(__dirname, 'source-before.json'), 'utf8')).files
const normalize = text => text.replace(/^\uFEFF/, '').split(/\r?\n/).map(line => line.trimEnd()).join('\n').trimEnd() + '\n'
const results = []
for (const file of files) {
  if (!file.path.startsWith('frontend/src/') || !/\.(ts|vue|css)$/.test(file.path)) continue
  const old = fs.readFileSync(path.join(before, file.path), 'utf8')
  const current = fs.readFileSync(path.join(root, file.path), 'utf8')
  let a, b, mode
  if (file.path.endsWith('.ts')) {
    const options = { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, removeComments: true } }
    a = ts.transpileModule(old, options).outputText
    b = ts.transpileModule(current, options).outputText
    mode = '锁定TypeScript转译后执行JS逐字相同'
  } else {
    a = normalize(old)
    b = normalize(current)
    mode = 'Vue/CSS仅规范行尾和尾空白后逐字相同'
  }
  assert.equal(a, b, file.path)
  results.push({ path: file.path, mode, sha256: crypto.createHash('sha256').update(b).digest('hex') })
}
fs.writeFileSync(path.join(__dirname, 'frontend-content-compare.json'), JSON.stringify({ passed: true, typescriptVersion: ts.version, files: results }, null, 2) + '\n')
console.log('PASS frontend content files=', results.length, 'TypeScript=', ts.version)
