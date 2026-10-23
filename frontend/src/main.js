import Vue from 'vue'
import ElementUI from 'element-ui'
import 'element-ui/lib/theme-chalk/index.css'
import './style.css'
import './styles/element-ui-compat.css'
import App from './App.vue'

/** 统一注册空间与弹道页面的 Element UI 控件，保留原 App 页面与业务数据流。 */
Vue.use(ElementUI)
new Vue({ render: (createElement) => createElement(App) }).$mount('#app')
