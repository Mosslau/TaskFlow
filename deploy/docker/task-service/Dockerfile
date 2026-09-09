# ============================================================
# TaskFlow task-service 运行镜像(基于预构建 fat jar 的简单可靠方式)
#
# 构建上下文 = deploy/(由 docker-compose.yml 指定 build.context: .)
# 前置:先执行 deploy/build.sh,生成 deploy/artifacts/task-service/task-service-1.0.0.jar
#
# 附件存储目录在容器内固定为 /taskflow/attachments(compose 绑定宿主
# deploy/data/attachments 便于备份),因此镜像无需额外配置。
# ============================================================
FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.title="taskflow/task-service" \
      org.opencontainers.image.description="TaskFlow 任务服务(端口 8082, 库 task_db, 附件落盘)"

WORKDIR /app

COPY artifacts/task-service/task-service-1.0.0.jar /app/app.jar

EXPOSE 8082

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
