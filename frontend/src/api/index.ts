import axios, { type AxiosInstance } from 'axios'
import router from '../router'

/**
 * axios 实例（接口设计文档 1.x）：
 * - 请求拦截：携带 Bearer token
 * - 响应拦截：拆 { code, message, data } 信封；401 跳登录
 */
const http: AxiosInstance = axios.create({
  baseURL: '/',
  timeout: 15000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('taskflow_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      return Promise.reject(new Error(body.message ?? '请求失败'))
    }
    return body
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('taskflow_token')
      router.push('/login')
    }
    return Promise.reject(error)
  },
)

export default http
