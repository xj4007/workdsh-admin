import { createApp } from 'vue'
import ArcoVue from '@arco-design/web-vue'
import '@arco-design/web-vue/dist/arco.css'
import Root from './Root.vue'
import './style.css'

createApp(Root).use(ArcoVue).mount('#app')
