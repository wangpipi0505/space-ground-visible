/// <reference types="node" />
import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 弹道页面与空间页面共用 Element Plus；本用例防止表单控件再次散落为原生标签并由私有 CSS 各自实现。
 * 浏览器文件选择、表格编辑及接口行为仍由对应视图测试覆盖，本检查只约束组件库边界。
 */
describe('A10 弹道页面组件库一致性', () => {
  const componentDirectories = [
    resolve(__dirname, '../components'),
    resolve(__dirname, '.'),
  ]
  const componentFiles = componentDirectories.flatMap((directory) =>
    readdirSync(directory)
      .filter((name) => /^Ballistic.*\.vue$/.test(name))
      .map((name) => resolve(directory, name)),
  )

  it('弹道视图和组件不直接实现原生表单、按钮或数据表控件', () => {
    const nativeControlPattern = /<\s*(?:input|select|textarea|button|table)\b/i
    const offenders = componentFiles
      .filter((file) => nativeControlPattern.test(readFileSync(file, 'utf8')))
      .map((file) => file.split(/[\\/]/).at(-1))

    expect(componentFiles.length).toBeGreaterThan(0)
    expect(offenders).toEqual([])
  })
})
