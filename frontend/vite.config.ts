import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      // 各模块前缀代理到网关（接口设计文档 1.1；网关端口 8000，8080 为 Nacos 控制台）
      '/auth': 'http://127.0.0.1:8000',
      '/task': 'http://127.0.0.1:8000',
      '/notification': 'http://127.0.0.1:8000',
      '/stats': 'http://127.0.0.1:8000',
    },
  },
})
