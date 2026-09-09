#!/usr/bin/env bash
# ============================================================
# TaskFlow 每日全量备份脚本
#
# 备份内容:
#   1) PostgreSQL 四个业务库(自定义格式 pg_dump -Fc):
#      auth_user_db / task_db / notification_db / stats_db
#   2) 附件目录(ATTACHMENT_ROOT,Compose 部署时指向宿主机 deploy/data/attachments)
#   3) 每个备份目录写 MANIFEST.txt 摘要
# 保留策略:按 BACKUP_KEEP_DAYS(默认 7)滚动删除过期备份,满足 RPO 24h。
#
# 依赖:pg_dump(PostgreSQL 16 客户端;macOS 可 brew install libpq 并把
#       /opt/homebrew/opt/libpq/bin 加入 PATH)
#
# 用法:
#   ./scripts/backup.sh
# cron 示例(每天 02:15):
#   15 2 * * * cd /opt/taskflow/deploy && ./scripts/backup.sh >> logs/backup.log 2>&1
# ============================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------- 载入环境变量(deploy/.env;缺失时用默认值,保证脚本可直接跑) ----------
ENV_FILE="${ENV_FILE:-$DEPLOY_DIR/.env}"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

# ---------- 参数解析(环境变量可覆盖,命令行参数优先) ----------
KEEP_DAYS="${BACKUP_KEEP_DAYS:-7}"
BACKUP_ROOT="${BACKUP_ROOT:-$DEPLOY_DIR/backups}"
case "$BACKUP_ROOT" in
  /*) ;;
  *)  BACKUP_ROOT="$DEPLOY_DIR/$BACKUP_ROOT" ;;
esac

STAMP="$(date +%Y%m%d_%H%M%S)"
DEST="$BACKUP_ROOT/$STAMP"
mkdir -p "$DEST"

# 附件目录(相对路径按 deploy/ 解析)
ATT_ROOT="${ATTACHMENT_ROOT:-$DEPLOY_DIR/data/attachments}"
case "$ATT_ROOT" in
  /*) ;;
  *)  ATT_ROOT="$DEPLOY_DIR/$ATT_ROOT" ;;
esac

DBS=(auth_user_db task_db notification_db stats_db)
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGUSER="${POSTGRES_USER:-postgres}"
PGPASSWORD="${POSTGRES_PASSWORD:-root}"
export PGPASSWORD

echo "==> TaskFlow 备份开始 $STAMP"
echo "    目标目录: $DEST | 保留天数: $KEEP_DAYS"

FAIL=0

# ---------- 1. pg_dump 四个库 ----------
if ! command -v pg_dump >/dev/null 2>&1; then
  echo "    ✘ 未找到 pg_dump,请安装 PostgreSQL 客户端(libpq)后重试" >&2
  FAIL=1
else
  for db in "${DBS[@]}"; do
    if pg_dump --no-owner --no-privileges \
        -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" \
        -Fc -f "$DEST/$db.dump" "$db" 2>"$DEST/$db.dump.err"; then
      size="$(du -h "$DEST/$db.dump" | cut -f1)"
      echo "    ✔ $db -> $DEST/$db.dump ($size)"
      rm -f "$DEST/$db.dump.err"
    else
      echo "    ✘ $db 备份失败,见 $DEST/$db.dump.err" >&2
      FAIL=1
    fi
  done
fi

# ---------- 2. 附件目录 ----------
if [ -d "$ATT_ROOT" ]; then
  if tar czf "$DEST/attachments.tar.gz" -C "$(dirname "$ATT_ROOT")" "$(basename "$ATT_ROOT")" 2>/dev/null; then
    echo "    ✔ 附件 $ATT_ROOT -> $DEST/attachments.tar.gz ($(du -h "$DEST/attachments.tar.gz" | cut -f1))"
  else
    echo "    ✘ 附件目录打包失败" >&2
    FAIL=1
  fi
else
  echo "    附件目录不存在,跳过: $ATT_ROOT"
fi

# ---------- 3. MANIFEST 摘要 ----------
{
  echo "TaskFlow backup manifest"
  echo "time:        $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "pg host:     $PGHOST:$PGPORT user=$PGUSER"
  echo "databases:   ${DBS[*]}"
  echo "attachments: $ATT_ROOT"
  echo "--- files ---"
  ls -lh "$DEST"
} > "$DEST/MANIFEST.txt"

# ---------- 4. 滚动清理(保留最近 KEEP_DAYS 天) ----------
if [ "${KEEP_DAYS:-7}" -gt 0 ] 2>/dev/null; then
  pruned="$(find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -name '20*' -mtime "+$KEEP_DAYS" | wc -l | tr -d ' ')"
  find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -name '20*' -mtime "+$KEEP_DAYS" -exec rm -rf {} + 2>/dev/null
  echo "    已清理 $pruned 个超过 ${KEEP_DAYS} 天的旧备份"
else
  echo "    警告:BACKUP_KEEP_DAYS 非法($KEEP_DAYS),跳过清理" >&2
fi

if [ "$FAIL" -ne 0 ]; then
  echo "==> 备份结束(存在失败项,请检查上方 ✘ 日志)" >&2
  exit 1
fi
echo "==> 备份完成: $DEST"
