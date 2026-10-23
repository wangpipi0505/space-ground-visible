/** Node 14 缺少浏览器 Fetch 与 Array.at；仅为单测补足原先 Node 24 提供的宿主 API。 */
import { afterEach, beforeEach } from 'vitest'
import fetch, { Response, Request, Headers } from 'node-fetch'
import { cleanupMounted } from './vueTestUtils'

Object.assign(globalThis, { Response, Request, Headers, fetch })
beforeEach(() => Object.assign(globalThis, { Response, Request, Headers, fetch }))
if (!Array.prototype.at) {
  Object.defineProperty(Array.prototype, 'at', {
    configurable: true,
    writable: true,
    value(index) {
      const n = Math.trunc(index) || 0
      return this[n < 0 ? this.length + n : n]
    },
  })
}
if (!Object.hasOwn) Object.hasOwn = (object, key) => Object.prototype.hasOwnProperty.call(object, key)
afterEach(cleanupMounted)
