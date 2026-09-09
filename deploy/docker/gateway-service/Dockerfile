# ============================================================
# TaskFlow gateway-service 运行镜像(基于预构建 fat jar 的简单可靠方式)
#
# 构建上下文 = deploy/(由 docker-compose.yml 指定 build.context: .)
# 前置:先执行 deploy/build.sh,生成 deploy/artifacts/gateway-service/gateway-service-1.0.0.jar
#       (jar 为 spring-boot:repackage 后的可执行 fat jar)
#
# 密钥只经运行时环境变量注入(compose environment / 裸机 env 文件),
# 镜像内不含任何敏感配置。
# ============================================================
FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.title="taskflow/gateway-service" \
      org.opencontainers.image.description="TaskFlow API 网关(Spring Cloud Gateway, 端口 8000)"

WORKDIR /app

# 仅 COPY 预构建 jar,不在镜像内做任何编译/下载
COPY artifacts/gateway-service/gateway-service-1.0.0.jar /app/app.jar

EXPOSE 8000

# JAVA_OPTS 可通过 compose environment 注入(如 -Xmx512m / -Duser.timezone=Asia/Shanghai)
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
