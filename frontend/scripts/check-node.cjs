/** 开发、测试和构建统一使用 Node 14.18 以上的 14.x；避免依赖在新版本 Node 下被意外升级。 */
const [major, minor] = process.versions.node.split('.').map(Number)
if (major !== 14 || minor < 18) {
  console.error(`当前 Node ${process.versions.node}，请切换至 Node 14.21.3 后执行命令。`)
  process.exit(1)
}
