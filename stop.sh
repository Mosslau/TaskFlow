#!/bin/bash
# TaskFlow 一键停止脚本
cd "$(dirname "$0")"
PID_DIR="$(pwd)/.pids"

for pidfile in "$PID_DIR"/*.pid; do
  [ -f "$pidfile" ] || continue
  name=$(basename "$pidfile" .pid)
  pid=$(cat "$pidfile")
  if kill -0 "$pid" 2>/dev/null; then
    # 杀掉进程组（mvn 会派生 java 子进程）
    pkill -P "$pid" 2>/dev/null
    kill "$pid" 2>/dev/null
    echo "已停止 $name (pid $pid)"
  else
    echo "$name 未在运行"
  fi
  rm -f "$pidfile"
done

# 兜底：清理残留的 spring-boot:run java 进程
pkill -f "taskflow" 2>/dev/null
echo "完成。"
