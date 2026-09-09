# ============================================================
# TaskFlow notification-service 运行镜像(基于预构建 fat jar 的简单可靠方式)
#
# 构建上下文 = deploy/(由 docker-compose.yml 指定 build.context: .)
# 前置:先执行 deploy/build.sh,生成 deploy/artifacts/notification-service/notification-service-1.0.0.jar
# ============================================================
FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.title="taskflow/notification-service" \
      org.opencontainers.image.description="TaskFlow 通知服务(端口 8083, 库 notification_db, RabbitMQ/SMTP)"

WORKDIR /app

COPY artifacts/notification-service/notification-service-1.0.0.jar /app/app.jar

EXPOSE 8083

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
