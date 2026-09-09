#!/usr/bin/env bash
# ============================================================
# TaskFlow 裸机(jar 直跑)一键停止 —— 停止 5 个后端服务
# 用法: ./scripts/stop-all.sh
# ============================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PID_DIR="${PID_DIR:-$DEPLOY_DIR/.pids}"

stopped=0
for pidfile in "$PID_DIR"/*.pid; do
  [ -f "$pidfile" ] || continue
  name="$(basename "$pidfile" .pid)"
  pid="$(cat "$pidfile")"
  if kill -0 "$pid" 2>/dev/null; then
    pkill -P "$pid" 2>/dev/null || true   # 先杀子进程(如有)
    kill "$pid" 2>/dev/null && { echo "已停止 $name(pid $pid)"; stopped=$((stopped + 1)); }
  else
    echo "$name 未在运行"
  fi
  rm -f "$pidfile"
done

# 兜底:清理残留的 java -jar 进程(pid 文件丢失或进程被重启过)
for svc in gateway-service auth-user-service task-service notification-service stats-service; do
  pkill -f "artifacts/$svc/$svc-1.0.0.jar" 2>/dev/null || true
done

echo "完成(共停止 $stopped 个进程)。"
