import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'happy-dom',
    // 挂载整个工作台视图的用例在慢速机器上可能超过默认 5 秒。
    testTimeout: 20000,
  },
})
