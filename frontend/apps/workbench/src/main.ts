import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/es/components/base/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import App from './App.vue'
import './styles/tokens.css'
import './styles.css'

createApp(App).use(createPinia()).mount('#app')
