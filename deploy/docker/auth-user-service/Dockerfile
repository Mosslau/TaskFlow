# ============================================================
# TaskFlow auth-user-service 运行镜像(基于预构建 fat jar 的简单可靠方式)
#
# 构建上下文 = deploy/(由 docker-compose.yml 指定 build.context: .)
# 前置:先执行 deploy/build.sh,生成 deploy/artifacts/auth-user-service/auth-user-service-1.0.0.jar
#
# 密钥只经运行时环境变量注入,镜像内不含任何敏感配置。
# ============================================================
FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.title="taskflow/auth-user-service" \
      org.opencontainers.image.description="TaskFlow 认证与用户服务(端口 8081, 库 auth_user_db)"

WORKDIR /app

COPY artifacts/auth-user-service/auth-user-service-1.0.0.jar /app/app.jar

EXPOSE 8081

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
