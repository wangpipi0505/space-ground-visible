/** 一次性迁移：擦除类型、保留业务函数，并机械转换 Vue/Element 的版本差异。 */
const fs = require('fs')
const path = require('path')
const ts = require('../runtime/A19/vue3-node_modules/.pnpm/typescript@6.0.3/node_modules/typescript')
const root = path.resolve(__dirname, '../..')
const frontend = path.join(root, 'frontend')
const baseline = path.join(root, 'verification/runtime/A19/baseline/frontend')

function walk(folder) { return fs.readdirSync(folder, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(folder, e.name)) : [path.join(folder, e.name)]) }
function erase(source, filename) {
  return ts.transpileModule(source, { fileName: filename, compilerOptions: { target: ts.ScriptTarget.ESNext, module: ts.ModuleKind.ESNext, verbatimModuleSyntax: true, removeComments: false, newLine: ts.NewLineKind.LineFeed } }).outputText
}
function macro(source) {
  const ast = ts.createSourceFile('component.ts', source, ts.ScriptTarget.Latest, true)
  const edits = []
  let options = ''
  function visit(node) {
    if (ts.isCallExpression(node) && ts.isIdentifier(node.expression)) {
      const name = node.expression.text
      if (name === 'defineOptions') {
        options = node.arguments[0].getText(ast)
        edits.push([node.getStart(ast), node.end, ''])
      }
      if (name === 'defineProps' && node.typeArguments) {
        const members = node.typeArguments[0].members
        if (!members) throw new Error('需人工处理非内联props: ' + node.getText(ast))
        const values = members.map(m => {
          const key = m.name.getText(ast)
          const kind = m.type.getText(ast)
          let type = 'null'
          if (kind === 'boolean') type = 'Boolean'
          else if (kind === 'number') type = 'Number'
          else if (kind === 'string') type = 'String'
          else if (ts.isFunctionTypeNode(m.type)) type = 'Function'
          else if (/\[\]$|^Array<|^TimeRange$/.test(kind)) type = 'Array'
          return `${key}: { type: ${type}${m.questionToken ? '' : ', required: true'} }`
        })
        edits.push([node.getStart(ast), node.end, `defineProps({ ${values.join(', ')} })`])
      }
      if (name === 'defineEmits' && node.typeArguments) {
        const names = node.typeArguments[0].members.map(m => m.name.getText(ast).replace(/^['"]|['"]$/g, ''))
        edits.push([node.getStart(ast), node.end, `defineEmits(${JSON.stringify(names)})`])
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(ast)
  for (const [start, end, text] of edits.sort((a, b) => b[0] - a[0])) source = source.slice(0, start) + text + source.slice(end)
  return { script: erase(source, 'component.ts'), options }
}
function imports(text, file) {
  text = text.replace(/import ElementPlus/g, 'import ElementUI').replace(/\bElementPlus\b/g, 'ElementUI').replace(/Element Plus/g, 'Element UI')
  text = text.replace(/\{ ElMessage, ElMessageBox \} from 'element-plus'/g, "{ Message as ElMessage, MessageBox as ElMessageBox } from 'element-ui'")
    .replace(/\{ ElMessage \} from 'element-plus'/g, "{ Message as ElMessage } from 'element-ui'")
    .replace(/ElementUI, \{ ElMessageBox \} from 'element-plus'/g, "ElementUI, { MessageBox as ElMessageBox } from 'element-ui'")
    .replace(/from 'element-plus'/g, "from 'element-ui'")
    .replace(/elementPlusTestHelpers/g, 'elementTestHelpers').replace(/installElementPlus/g, 'installElementUI')
    .replace(/(\.vm\.\$emit\()'update:modelValue'/g, "$1'input'")
    .replace(/\.unmount\(\)/g, '.destroy()')
  if (file.endsWith('.spec.ts') || file.includes('/test/')) {
    let relative = path.relative(path.dirname(file), path.join(baseline, 'src/test/vueTestUtils')).replace(/\\/g, '/')
    if (!relative.startsWith('.')) relative = './' + relative
    text = text.replace(/from '@vue\/test-utils'/g, `from '${relative}'`)
  }
  return text
}
function template(text) {
  return text.replace(/<\/?el-[^>]+>/g, tag => {
    const name = /^<\/?(el-[\w-]+)/.exec(tag)[1]
    tag = tag.replace(/:model-value=/g, /el-(dialog|drawer)/.test(name) ? ':visible=' : ':value=')
      .replace(/@update:model-value=/g, '@input=')
      .replace(/v-model:([\w-]+)=/g, ':$1.sync=')
    if (/el-(dialog|drawer)/.test(name)) tag = tag.replace(/\bv-model=/g, ':visible.sync=').replace(/\s:teleported="false"/g, '')
    if (/el-checkbox(?:-button)?$/.test(name)) tag = tag.replace(/:value="(state\.value|satellite\.id|station\.id)"/g, ':label="$1"')
    if (name === 'el-button' && /\stext\b/.test(tag)) {
      const type = /\btype="([^"]+)"/.exec(tag)
      tag = tag.replace(/\stext\b/, '').replace(/\stype="[^"]+"/, '')
      tag = tag.replace('<el-button', `<el-button type="text"${type ? ` data-text-type="${type[1]}"` : ''}`)
    }
    return tag
  }).replace(/v-model:([\w-]+)=/g, ':$1.sync=')
    .replace(/@update:model-value=/g, '@update:modelValue=')
    .replace(/@select-type=/g, '@selectType=')
    .replace(/@edit-payloads=/g, '@editPayloads=')
    .replace(/\$event as TimeRange/g, '$event')
    .replace(/\(value: unknown\)/g, '(value)')
}
for (const source of walk(path.join(baseline, 'src'))) {
  const relative = path.relative(baseline, source)
  if (/\.d\.ts$/.test(source)) continue
  if (!/\.(ts|vue)$/.test(source)) continue
  if (process.argv.includes('--vue-only') && !source.endsWith('.vue')) continue
  let content = fs.readFileSync(source, 'utf8')
  let target = path.join(frontend, relative.replace(/\.ts$/, '.js').replace('installElementPlus', 'installElementUI').replace('elementPlusTestHelpers', 'elementTestHelpers').replace('BallisticElementPlusConsistency', 'BallisticElementConsistency'))
  if (source.endsWith('.vue')) {
    let normal = ''
    content = content.replace(/<script setup lang="ts">([\s\S]*?)<\/script>/, (_, script) => {
      const result = macro(script)
      normal = result.options ? `<script>\nexport default ${result.options}\n</script>\n\n` : ''
      return `<script setup>\n${imports(result.script.replace(/^export \{\};\s*$/gm, ''), source)}</script>`
    })
    content = normal + template(content)
  } else {
    content = imports(erase(content, source), source)
  }
  fs.writeFileSync(target, content)
  if (target !== path.join(frontend, relative) && fs.existsSync(path.join(frontend, relative))) fs.unlinkSync(path.join(frontend, relative))
}
console.log('已转换所有 Vue 脚本和 JavaScript 模块；尚需兼容回归。')
