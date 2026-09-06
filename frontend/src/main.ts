import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './styles/theme.scss'
import App from './App.vue'
import router from './router'
import { vPerm } from './directives/perm'
import { vColResizable } from './directives/colResizable'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.directive('perm', vPerm)
app.directive('col-resizable', vColResizable)

app.mount('#app')
