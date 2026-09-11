#!/bin/bash
# TaskFlow 一键启动脚本
# 用法: ./start.sh [--frontend]   默认只起后端，加 --frontend 同时起前端
#       ./stop.sh                 停止所有服务

set -u
cd "$(dirname "$0")"
ROOT="$(pwd)"

# ---------- 0. 加载项目根 .env（若存在）----------
# 用于注入 SMTP 等环境变量；.env 已加入 .gitignore，凭据不入库。
# set -a 让文件内所有赋值自动 export，供后续 mvn/java 子进程继承。
if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
  echo "==> 已加载 $ROOT/.env"
fi

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
MVN_REPO="$ROOT/.m2/repository"
LOG_DIR="$ROOT/logs"
PID_DIR="$ROOT/.pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

SERVICES=(gateway-service auth-user-service task-service notification-service stats-service)
PING=(8000 8081 8082 8083 8084)
PING_PATH=("" "auth" "task" "notification" "stats")

c_green() { printf "\033[32m%s\033[0m\n" "$1"; }
c_red()   { printf "\033[31m%s\033[0m\n" "$1"; }
c_yellow(){ printf "\033[33m%s\033[0m\n" "$1"; }

# ---------- 1. 中间件检查 ----------
echo "==> 检查中间件 (brew services)..."
for mw in postgresql@18 redis rabbitmq; do
  if brew services list | grep -E "^$mw\s" | grep -q started; then
    c_green "    $mw ✔"
  else
    c_yellow "    $mw 未启动，尝试启动..."
    brew services start "$mw"
  fi
done

# ---------- 2. Nacos ----------
echo "==> 检查 Nacos..."
if curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8848/nacos/ 2>/dev/null | grep -q 200; then
  c_green "    Nacos 已在运行 ✔"
else
  c_yellow "    启动 Nacos (standalone)..."
  sh ~/Downloads/nacos/bin/startup.sh -m standalone
  for i in $(seq 1 30); do
    sleep 2
    curl -s -o /dev/null http://127.0.0.1:8848/nacos/ && break
  done
fi

# ---------- 3. 安装父 POM 和 common（单模块运行不走 reactor）----------
echo "==> 安装父 POM 与 common 到工作区仓库..."
mvn install -N -q -Dmaven.repo.local="$MVN_REPO" || { c_red "父 POM 安装失败"; exit 1; }
mvn install -pl common -q -DskipTests -Dmaven.repo.local="$MVN_REPO" || { c_red "common 安装失败"; exit 1; }
c_green "    依赖就绪 ✔"

# ---------- 4. 启动 5 个微服务 ----------
for i in "${!SERVICES[@]}"; do
  svc="${SERVICES[$i]}"
  port="${PING[$i]}"
  pidfile="$PID_DIR/$svc.pid"

  # 已运行则跳过
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    c_green "==> $svc 已在运行 (pid $(cat "$pidfile"))，跳过"
    continue
  fi
  # 端口被占则跳过
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    c_yellow "==> $svc 端口 $port 已被占用，跳过"
    continue
  fi

  echo "==> 启动 $svc (端口 $port)..."
  nohup mvn spring-boot:run -pl "$svc" -Dmaven.repo.local="$MVN_REPO" \
    > "$LOG_DIR/$svc.log" 2>&1 &
  echo $! > "$pidfile"
done

# ---------- 5. 等待健康检查 ----------
echo "==> 等待服务就绪..."
for i in "${!SERVICES[@]}"; do
  svc="${SERVICES[$i]}"; port="${PING[$i]}"; path="${PING_PATH[$i]}"
  if [ "$svc" = "gateway-service" ]; then
    url="http://127.0.0.1:$port/auth/api/v1/ping"
  else
    url="http://127.0.0.1:$port/$path/api/v1/ping"
  fi
  ok=""
  for j in $(seq 1 45); do
    sleep 2
    body=$(curl -s --max-time 2 "$url" 2>/dev/null)
    if echo "$body" | grep -q '"code":0'; then ok=1; break; fi
  done
  if [ -n "$ok" ]; then
    c_green "    $svc ✔  $url"
  else
    c_red "    $svc ✘  未就绪，查看日志: $LOG_DIR/$svc.log"
  fi
done

# ---------- 6. 前端（可选）----------
if [ "${1:-}" = "--frontend" ]; then
  echo "==> 启动前端 (Vite dev server, :5173)..."
  cd "$ROOT/frontend"
  [ -d node_modules ] || pnpm install --store-dir "$ROOT/.cache/store"
  nohup pnpm dev > "$LOG_DIR/frontend.log" 2>&1 &
  echo $! > "$PID_DIR/frontend.pid"
  c_green "    前端: http://localhost:5173"
fi

echo
c_green "完成！网关: http://127.0.0.1:8000 | Nacos 控制台: http://localhost:8080 (控制台认证已关闭，免登录)"
echo "日志目录: $LOG_DIR | 停止: ./stop.sh"
