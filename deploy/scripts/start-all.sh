#!/usr/bin/env bash
# ============================================================
# TaskFlow 裸机(jar 直跑)一键启动 —— 启动 5 个后端服务
#
# 前置:
#   1) 在 deploy/ 下执行 ./build.sh      产出 deploy/artifacts/*.jar
#   2) cp .env.example .env 并按需修改   各服务环境变量由 deploy/.env 注入
#   3) 本机已就绪:PostgreSQL 16(含 4 库)、Redis、RabbitMQ、Nacos(standalone)
#      —— 参考项目根 start.sh 的中间件启动方式,或 docs/本地中间件.md
#
# 用法: ./scripts/start-all.sh
# 停止: ./scripts/stop-all.sh
# 前端: 由宿主机 Nginx 托管构建产物(见 deploy/nginx.conf)
# ============================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="${LOG_DIR:-$DEPLOY_DIR/logs}"
PID_DIR="${PID_DIR:-$DEPLOY_DIR/.pids}"
mkdir -p "$LOG_DIR" "$PID_DIR"

# ---------- 载入环境变量(deploy/.env) ----------
ENV_FILE="${ENV_FILE:-$DEPLOY_DIR/.env}"
if [ ! -f "$ENV_FILE" ]; then
  echo "[start-all] 缺少 $ENV_FILE" >&2
  echo "[start-all] 请先执行: cd $DEPLOY_DIR && cp .env.example .env 并按需修改" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

# ---------- 附件目录:相对路径按 deploy/ 解析 ----------
ATT_ROOT="${ATTACHMENT_ROOT:-$DEPLOY_DIR/data/attachments}"
case "$ATT_ROOT" in
  /*) ;;
  *)  ATT_ROOT="$DEPLOY_DIR/$ATT_ROOT" ;;
esac
ATTACHMENT_ROOT="$ATT_ROOT"
mkdir -p "$ATT_ROOT"

# ---------- 定位 JDK ----------
JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="$(command -v java 2>/dev/null || true)"
fi
if [ -z "$JAVA_BIN" ] || ! "$JAVA_BIN" -version >/dev/null 2>&1; then
  echo "[start-all] 未找到可用 JDK17,请安装或设置 JAVA_HOME" >&2
  exit 1
fi

if [ -z "${JWT_SECRET:-}" ]; then
  echo "[start-all] 警告:JWT_SECRET 为空,将回退 application.yml 开发默认值(不安全,生产必填)" >&2
fi

# ---------- 服务定义:名称|端口|ping 前缀(空=经网关)|数据库名 ----------
SERVICES=(
  "gateway-service|8000||"
  "auth-user-service|8081|auth|auth_user_db"
  "task-service|8082|task|task_db"
  "notification-service|8083|notification|notification_db"
  "stats-service|8084|stats|stats_db"
)

start_one() {
  local meta svc rest port ping db jar pidfile
  meta="$1"
  svc="${meta%%|*}";       rest="${meta#*|}"
  port="${rest%%|*}";      rest="${rest#*|}"
  ping="${rest%%|*}";      rest="${rest#*|}"
  db="$rest"
  jar="$DEPLOY_DIR/artifacts/$svc/$svc-1.0.0.jar"
  pidfile="$PID_DIR/$svc.pid"

  [ -f "$jar" ] || { echo "[start-all] 缺少 $jar —— 请先在 deploy/ 下执行 ./build.sh" >&2; return 1; }
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    echo "[start-all] $svc 已在运行(pid $(cat "$pidfile")),跳过"
    return 0
  fi

  # ---------- 服务环境(全部经环境变量注入,不写死敏感值) ----------
  # 注:env 覆盖 application.yml 中写死的 127.0.0.1 / 默认口令
  export SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR="${NACOS_ADDR:-127.0.0.1:8848}"
  export SPRING_CLOUD_NACOS_CONFIG_SERVER_ADDR="${NACOS_ADDR:-127.0.0.1:8848}"
  export SPRING_DATA_REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
  export SPRING_DATA_REDIS_PORT="${REDIS_PORT:-6379}"
  export SPRING_DATA_REDIS_PASSWORD="${REDIS_PASSWORD:-root}"
  export SPRING_RABBITMQ_HOST="${RABBITMQ_HOST:-127.0.0.1}"
  export SPRING_RABBITMQ_PORT="${RABBITMQ_PORT:-5672}"
  export SPRING_RABBITMQ_USERNAME="${RABBITMQ_USER:-guest}"
  export SPRING_RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-guest}"
  export SPRING_RABBITMQ_VIRTUAL_HOST="/"
  export ATTACHMENT_ROOT

  if [ -n "$db" ]; then
    export SPRING_DATASOURCE_URL="jdbc:postgresql://${PGHOST:-127.0.0.1}:${PGPORT:-5432}/$db"
    export SPRING_DATASOURCE_USERNAME="${POSTGRES_USER:-postgres}"
    export SPRING_DATASOURCE_PASSWORD="${POSTGRES_PASSWORD:-root}"
  else
    unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD 2>/dev/null || true
  fi

  echo "[start-all] 启动 $svc(端口 $port)..."
  cd "$DEPLOY_DIR"
  nohup "$JAVA_BIN" ${JAVA_OPTS:-} -jar "$jar" > "$LOG_DIR/$svc.log" 2>&1 &
  echo $! > "$pidfile"
}

# ---------- 启动全部服务 ----------
for meta in "${SERVICES[@]}"; do
  start_one "$meta"
done

# ---------- 等待健康检查(与项目根 start.sh 同款 ping 约定) ----------
echo "==> 等待服务就绪(最长约 90 秒)..."
READY=0
for meta in "${SERVICES[@]}"; do
  svc="${meta%%|*}";       rest="${meta#*|}"
  port="${rest%%|*}";      rest="${rest#*|}"
  ping="${rest%%|*}"
  if [ "$svc" = "gateway-service" ]; then
    url="http://127.0.0.1:$port/auth/api/v1/ping"
  else
    url="http://127.0.0.1:$port/$ping/api/v1/ping"
  fi
  ok=""
  for _ in $(seq 1 45); do
    sleep 2
    body="$(curl -s --max-time 2 "$url" 2>/dev/null || true)"
    if printf '%s' "$body" | grep -q '"code":0'; then ok=1; break; fi
  done
  if [ -n "$ok" ]; then
    echo "    ✔ $svc   $url"; READY=$((READY + 1))
  else
    echo "    ✘ $svc 未就绪,查看日志: $LOG_DIR/$svc.log"
  fi
done

echo
echo "就绪 $READY/5 个服务。网关: http://127.0.0.1:8000 | 日志: $LOG_DIR | 停止: ./scripts/stop-all.sh"
[ "$READY" -eq 5 ] || echo "提示:网关未就绪前,请确认 Nacos(8848)/PostgreSQL/Redis/RabbitMQ 已启动且 .env 口令正确。"
