import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue2'

/** 使用支持 Node 14 的测试版本，保留原组件回归与真实 Element UI 控件。 */
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'happy-dom',
    setupFiles: ['./src/test/setup.js'],
    testTimeout: 20000,
    threads: false,
  },
})
