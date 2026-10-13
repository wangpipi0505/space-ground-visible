/** T28 验收网关：Node 标准库服务正式 dist 并按原路径流式代理；不能冒充 Nginx 已运行。 */
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(process.argv[2])
const backend = new URL(process.argv[3])
const port = Number(process.argv[4])
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' }
http.createServer((request, response) => {
  if (request.url.startsWith('/api/')) {
    const proxy = http.request({ hostname: backend.hostname, port: backend.port, path: request.url, method: request.method, headers: { ...request.headers, host: backend.host } }, upstream => {
      response.writeHead(upstream.statusCode, upstream.headers)
      upstream.pipe(response)
    })
    proxy.on('error', error => { response.writeHead(502); response.end(error.message) })
    request.pipe(proxy)
    return
  }
  let requested
  try { requested = decodeURIComponent(new URL(request.url, 'http://localhost').pathname) } catch { response.writeHead(400); response.end(); return }
  let file = path.resolve(root, '.' + requested)
  if (file !== root && !file.startsWith(root + path.sep)) { response.writeHead(403); response.end(); return }
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) file = path.join(root, 'index.html')
  response.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' })
  fs.createReadStream(file).pipe(response)
}).listen(port, '127.0.0.1', () => console.log(`T28_STATIC_READY ${port} root=${root} api=${backend.href}`))
