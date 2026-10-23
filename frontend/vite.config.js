import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue2'

/** Vue 2.7 保留组合式业务代码；接口代理与迁移前保持一致。 */
export default defineConfig({
  plugins: [vue()],
  // Element UI的CommonJS依赖与页面的ES模块必须共享同一个Vue实例，防止生产包引入两套响应式运行时。
  resolve: {
    alias: [{ find: /^vue$/, replacement: 'vue/dist/vue.runtime.esm.js' }],
    dedupe: ['vue'],
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.VITE_DEV_API_TARGET || 'http://localhost:8080', changeOrigin: true },
    },
  },
})
