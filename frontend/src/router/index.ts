import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/login/LoginView.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/',
      component: () => import('../layouts/MainLayout.vue'),
      redirect: '/tasks',
      children: [
        {
          path: 'tasks',
          name: 'tasks',
          component: () => import('../views/task/TaskListView.vue'),
          meta: { title: '任务列表' },
        },
        {
          path: 'perm',
          name: 'perm',
          component: () => import('../views/perm/PermView.vue'),
          meta: { title: '权限管理' },
        },
        // 统计总览 / 日程 / 通知中心：后续里程碑接入
      ],
    },
  ],
})

// 路由守卫：无 token 跳登录
router.beforeEach((to) => {
  const token = localStorage.getItem('taskflow_token')
  if (!token && to.path !== '/login') {
    return '/login'
  }
  if (token && to.path === '/login') {
    return '/'
  }
  return true
})

export default router
