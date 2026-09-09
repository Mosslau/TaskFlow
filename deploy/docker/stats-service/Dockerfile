# ============================================================
# TaskFlow stats-service 运行镜像(基于预构建 fat jar 的简单可靠方式)
#
# 构建上下文 = deploy/(由 docker-compose.yml 指定 build.context: .)
# 前置:先执行 deploy/build.sh,生成 deploy/artifacts/stats-service/stats-service-1.0.0.jar
# ============================================================
FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.title="taskflow/stats-service" \
      org.opencontainers.image.description="TaskFlow 统计服务(端口 8084, 库 stats_db)"

WORKDIR /app

COPY artifacts/stats-service/stats-service-1.0.0.jar /app/app.jar

EXPOSE 8084

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
