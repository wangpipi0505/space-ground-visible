/** 核对纯业务模块函数体；忽略类型、注释及格式，单列 Vue 2 响应式所需的兼容变更。 */
const fs = require('fs')
const path = require('path')
const ts = require('../runtime/A19/vue3-node_modules/.pnpm/typescript@6.0.3/node_modules/typescript')
const root = path.resolve(__dirname, '../..')
const original = path.join(root, 'verification/runtime/A19/baseline/frontend/src')
const current = path.join(root, 'frontend/src')
function canonical(node) {
  if (ts.isParenthesizedExpression(node)) return canonical(node.expression)
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return [node.kind, node.text]
  if (ts.isNumericLiteral(node)) return [node.kind, Number(node.text)]
  if (ts.isIdentifier(node)) return [node.kind, node.text]
  if (ts.isRegularExpressionLiteral(node)) return [node.kind, node.text]
  if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.arguments[0]?.getText() === 'satellitePayloadForms') {
    const access = [ts.SyntaxKind.ElementAccessExpression, canonical(node.arguments[0]), canonical(node.arguments[1])]
    if (node.expression.text === 'set') return [ts.SyntaxKind.BinaryExpression, access, [ts.SyntaxKind.EqualsToken], canonical(node.arguments[2])]
    if (node.expression.text === 'del') return [ts.SyntaxKind.DeleteExpression, access]
  }
  const children = []
  ts.forEachChild(node, child => { children.push(canonical(child)) })
  return [node.kind, ...children]
}
function walk(folder) { return fs.readdirSync(folder, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(folder, e.name)) : [path.join(folder, e.name)]) }
function functions(text) {
  const ast = ts.createSourceFile('file.js', text, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS)
  const values = []
  function visit(node) {
    if (ts.isFunctionDeclaration(node) && node.name && node.body) values.push({ name: node.name.text, body: canonical(node.body) })
    ts.forEachChild(node, visit)
  }
  visit(ast)
  return values
}
const records = []
for (const folder of ['api', 'utils', 'composables']) {
  for (const file of walk(path.join(original, folder)).filter(file => file.endsWith('.ts') && !file.endsWith('.spec.ts'))) {
    const relative = path.relative(original, file)
    const before = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ESNext, module: ts.ModuleKind.ESNext, verbatimModuleSyntax: true } }).outputText
    const after = fs.readFileSync(path.join(current, relative.replace(/\.ts$/, '.js')), 'utf8')
    const a = functions(before), b = functions(after)
    if (JSON.stringify(a) !== JSON.stringify(b)) throw new Error(relative + ' 函数体存在未解释变化')
    records.push({ file: relative.replace(/\\/g, '/').replace(/\.ts$/, '.js'), functions: a.length, equal: true })
  }
}
fs.writeFileSync(path.join(root, 'verification/A19/runtime-comparison.json'), JSON.stringify({ records, functions: records.reduce((n, r) => n + r.functions, 0), allowedCompatibility: '仅useVisibilityWorkbench字典写入/删除改用Vue.set/del' }, null, 2))
console.log(JSON.stringify({ files: records.length, functions: records.reduce((n, r) => n + r.functions, 0), equal: true }))
