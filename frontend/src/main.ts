import { createApp } from 'vue'
/** 注册原空间工程的 Element Plus 组件及样式，在浏览器根节点挂载规划工作台。 */
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'

createApp(App).use(ElementPlus).mount('#app')
