import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/login/LoginView.vue'),
    },
    {
      path: '/',
      name: 'home',
      component: () => import('../views/home/HomeView.vue'),
    },
  ],
})

// 路由守卫：无 token 跳登录
router.beforeEach((to) => {
  const token = localStorage.getItem('taskflow_token')
  if (!token && to.path !== '/login') {
    return '/login'
  }
  return true
})

export default router
