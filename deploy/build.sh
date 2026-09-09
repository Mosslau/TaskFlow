#!/usr/bin/env bash
# ============================================================
# TaskFlow M6.3 本地构建脚本(在 deploy/ 目录执行 ./build.sh)
#
# 产出:
#   deploy/artifacts/<service>/<service>-1.0.0.jar   —— 五个可执行 Spring Boot fat jar
#   deploy/artifacts/frontend-dist/                  —— 前端静态产物(Vite build)
#
# 之后两种部署方式二选一:
#   Docker Compose: cd deploy && docker compose up -d --build
#   裸机:           配置 deploy/.env 后 deploy/scripts/start-all.sh
#
# 依赖:JDK 17(Maven 解析用)、Maven 3.8+、Node.js 18+/pnpm(构建前端)
# 可选:MVN_REPO 环境变量指定 Maven 本地仓库(默认复用项目 .m2/repository)
#
# 说明:父 POM 未继承 spring-boot-starter-parent,spring-boot-maven-plugin 的
#       repackage 不会自动绑定到 package 生命周期,因此这里显式对五个服务
#       执行 package spring-boot:repackage 生成可执行 fat jar。
# ============================================================
set -euo pipefail

# ---------- 路径 ----------
DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$DEPLOY_DIR/.." && pwd)"
ART_DIR="$DEPLOY_DIR/artifacts"
MVN_REPO="${MVN_REPO:-$PROJECT_ROOT/.m2/repository}"
VERSION="${TASKFLOW_VERSION:-1.0.0}"          # 与父 pom <version> 保持一致
SERVICES=(gateway-service auth-user-service task-service notification-service stats-service)

log()  { printf "\033[32m[build]\033[0m %s\n" "$*"; }
warn() { printf "\033[33m[build]\033[0m %s\n" "$*"; }
die()  { printf "\033[31m[build] 错误: %s\n\033[0m" "$*" >&2; exit 1; }

command -v mvn  >/dev/null 2>&1 || die "未找到 mvn,请安装 Maven 3.8+(并确保 JAVA_HOME 指向 JDK17)"
command -v pnpm >/dev/null 2>&1 || die "未找到 pnpm,请安装 Node 18+ 与 pnpm"
command -v node >/dev/null 2>&1 || die "未找到 node,请安装 Node 18+"

# ---------- 0. 工具版本提示 ----------
JAVA_VER="$(java -version 2>&1 | head -1)"
log "Maven: $(mvn -v 2>/dev/null | head -1)"
log "Java : ${JAVA_VER:-未检测到(将使用 mvn 解析的 JDK)}"
log "Node : $(node -v) / pnpm $(pnpm -v 2>/dev/null)"
[ "${JAVA_VER#*version \"17}" != "$JAVA_VER" ] || warn "建议使用 JDK 17(当前: ${JAVA_VER:-未知})"

# ---------- 1. 后端:安装父 POM + common 到本地仓库 ----------
log "1/4 安装父 POM 与 common ..."
( cd "$PROJECT_ROOT" && mvn install -N -q -DskipTests -Dmaven.repo.local="$MVN_REPO" )
( cd "$PROJECT_ROOT" && mvn install -pl common -q -DskipTests -Dmaven.repo.local="$MVN_REPO" )

# ---------- 2. 后端:逐个服务 package + repackage(生成可执行 fat jar) ----------
log "2/4 编译并打包 5 个服务(显式 spring-boot:repackage) ..."
for svc in "${SERVICES[@]}"; do
  log "    → $svc"
  ( cd "$PROJECT_ROOT" && mvn -pl "$svc" package spring-boot:repackage -q \
      -DskipTests -Dmaven.repo.local="$MVN_REPO" ) \
    || die "$svc package/repackage 失败"
done

# ---------- 3. 复制 fat jar 到 deploy/artifacts ----------
log "3/4 汇总产物到 deploy/artifacts ..."
rm -rf "$ART_DIR"
for svc in "${SERVICES[@]}"; do
  src="$PROJECT_ROOT/$svc/target/$svc-$VERSION.jar"
  [ -f "$src" ] || die "缺少 $src(检查 VERSION 是否与父 pom 一致)"
  mkdir -p "$ART_DIR/$svc"
  cp "$src" "$ART_DIR/$svc/$svc-$VERSION.jar"
  log "    ✔ $svc-$VERSION.jar ($(du -h "$ART_DIR/$svc/$svc-$VERSION.jar" | cut -f1))"
done

# ---------- 4. 前端:pnpm 安装 + 构建 ----------
log "4/4 构建前端(frontend/dist → artifacts/frontend-dist) ..."
(
  cd "$PROJECT_ROOT/frontend"
  pnpm install --frozen-lockfile --store-dir "$PROJECT_ROOT/.cache/pnpm-store" >/dev/null
  pnpm build
) || die "前端构建失败"
cp -r "$PROJECT_ROOT/frontend/dist" "$ART_DIR/frontend-dist"
log "    ✔ frontend-dist ($(du -sh "$ART_DIR/frontend-dist" | cut -f1))"

# ---------- 完成 ----------
log "构建完成,产物: $ART_DIR"
echo
cat <<'EOF'
下一步(二选一):
  A) Docker Compose:
       cd deploy
       cp .env.example .env          # 并修改敏感项
       docker compose config         # 可选:语法校验
       docker compose up -d --build
  B) 裸机(jar + 本机中间件 + Nginx):
       cd deploy
       cp .env.example .env
       ./scripts/start-all.sh        # 启动 5 个服务
       参考 deploy/nginx.conf 配置宿主机 Nginx 托管前端
EOF
