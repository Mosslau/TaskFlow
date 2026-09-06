import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      // 各模块前缀代理到网关（接口设计文档 1.1；网关端口 8000，8080 为 Nacos 控制台）
      // 注意：必须收窄到 /api 前缀——'/task' 会误吞前端路由 '/tasks'（前缀匹配），
      // 导致前端页面被代理到网关返回 404（2026-09-06 踩坑）
      '/auth/api': 'http://127.0.0.1:8000',
      '/task/api': 'http://127.0.0.1:8000',
      '/notification/api': 'http://127.0.0.1:8000',
      '/stats/api': 'http://127.0.0.1:8000',
    },
  },
})
