/** 仅在需要全局组件的视图单测中安装，与正式入口的插件配置保持一致。 */
import { config } from '@vue/test-utils'
import ElementPlus from 'element-plus'

config.global.plugins = [...(config.global.plugins ?? []), ElementPlus]
