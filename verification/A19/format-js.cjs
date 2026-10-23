/** 统一迁移后的 JavaScript 为双空格；Vue 模板和原业务样式不参与格式改写。 */
const fs = require('fs')
const path = require('path')
const prettier = require('../../frontend/node_modules/.pnpm/prettier@2.8.8/node_modules/prettier')
const root = path.resolve(__dirname, '../../frontend')
const options = { parser: 'babel', tabWidth: 2, semi: false, singleQuote: true, printWidth: 120, trailingComma: 'all' }
function walk(folder) { return fs.readdirSync(folder, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(folder, e.name)) : [path.join(folder, e.name)]) }
for (const file of [...walk(path.join(root, 'src')), ...walk(path.join(root, 'scripts')), path.join(root, 'vite.config.js'), path.join(root, 'vitest.config.js')]) {
  const text = fs.readFileSync(file, 'utf8')
  if (/\.(js|cjs)$/.test(file)) fs.writeFileSync(file, prettier.format(text, options))
  else if (file.endsWith('.vue')) fs.writeFileSync(file, text.replace(/(<script(?: setup)?>)([\s\S]*?)(<\/script>)/g, (_, open, script, close) => open + '\n' + prettier.format(script, options) + close))
}
