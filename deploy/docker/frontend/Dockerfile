# ============================================================
# TaskFlow 前端镜像(Vue3+Vite 产物经 Nginx 托管,并反代 /auth /task /notification /stats
# 到内部网关容器 gateway:8000)
#
# 构建上下文 = deploy/
# 前置:先执行 deploy/build.sh,生成 deploy/artifacts/frontend-dist/
# 对外端口:8080(见 docker-compose.yml ports 映射)
# ============================================================
FROM nginx:1.27-alpine

LABEL org.opencontainers.image.title="taskflow/frontend" \
      org.opencontainers.image.description="TaskFlow Web 前端(Nginx, 端口 8080)"

# 静态资源
COPY artifacts/frontend-dist /usr/share/nginx/html

# 容器内 Nginx 站点配置(server 8080;API 反代到 gateway 容器)
COPY docker/frontend/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
