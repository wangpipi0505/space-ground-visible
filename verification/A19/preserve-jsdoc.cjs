/** 将类型说明保留为 JSDoc；不产生运行时模型或重复的数据校验。 */
const fs = require('fs')
const path = require('path')
const ts = require('../runtime/A19/vue3-node_modules/.pnpm/typescript@6.0.3/node_modules/typescript')
const root = path.resolve(__dirname, '../..')
const baseline = path.join(root, 'verification/runtime/A19/baseline/frontend/src')
const target = path.join(root, 'frontend/src')
const printer = ts.createPrinter({ removeComments: true })
function typeText(type, ast) { return printer.printNode(ts.EmitHint.Unspecified, type, ast).replace(/\s+/g, ' ') }
function walk(folder) { return fs.readdirSync(folder, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(folder, e.name)) : [path.join(folder, e.name)]) }
for (const source of walk(baseline)) {
  if (!source.endsWith('.ts') || source.endsWith('.spec.ts') || source.endsWith('.d.ts') || source.includes(path.sep + 'test' + path.sep)) continue
  const relative = path.relative(baseline, source).replace(/\.ts$/, '.js')
  const file = path.join(target, relative)
  const text = fs.readFileSync(source, 'utf8')
  const ast = ts.createSourceFile(source, text, ts.ScriptTarget.Latest, true)
  const docs = []
  for (const node of ast.statements) {
    if (ts.isInterfaceDeclaration(node)) {
      const original = node.jsDoc?.map(d => d.comment).filter(Boolean).join('\n') || node.name.text
      const lines = [`/**`, ` * ${original}`]
      for (const type of node.typeParameters || []) lines.push(` * @template ${type.name.text}`)
      lines.push(` * @typedef {Object} ${node.name.text}`)
      for (const heritage of node.heritageClauses || []) lines.push(` * 继承字段：${heritage.types.map(t => t.getText(ast)).join('、')}。`)
      for (const member of node.members) {
        if (!member.name || !member.type) continue
        const name = member.name.getText(ast)
        const comment = member.jsDoc?.map(d => d.comment).filter(Boolean).join('；') || ''
        lines.push(` * @property {${typeText(member.type, ast)}} ${member.questionToken ? `[${name}]` : name}${comment ? ' ' + comment : ''}`)
      }
      lines.push(' */')
      docs.push(lines.join('\n'))
    } else if (ts.isTypeAliasDeclaration(node)) {
      const comment = node.jsDoc?.map(d => d.comment).filter(Boolean).join('；') || node.name.text
      docs.push(`/** ${comment}\n * @typedef {${typeText(node.type, ast)}} ${node.name.text}\n */`)
    }
  }
  if (docs.length) {
    let existing = fs.readFileSync(file, 'utf8')
    const previous = existing.indexOf('@typedef')
    if (previous >= 0) existing = existing.slice(0, existing.lastIndexOf('/**', previous))
    fs.writeFileSync(file, existing.replace(/\s*$/, '\n') + '\n' + docs.join('\n\n') + '\n')
  }
}
console.log('接口与配置的字段、单位和可空说明已保留为 JSDoc。')
