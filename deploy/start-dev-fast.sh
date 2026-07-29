#!/bin/bash
# ============================================
# AgentX 快速开发启动脚本 (Linux/Mac)
#
# 与完整 Docker 部署方案共存，互不影响：
#   完整方案：docker compose --profile local up -d
#   快速方案：本脚本（Docker 仅跑基础设施 + 本地热重载后端）
# ============================================

set -e

echo ""
echo "  🔥 AgentX 快速开发模式（后端热重载）"
echo "  Docker 仅运行数据库/消息队列，后端本地运行"
echo ""

# 切换到脚本所在目录
cd "$(dirname "$0")"

echo "  [1/2] 启动基础设施服务（postgres + rabbitmq + redis）..."
docker compose --profile local up -d postgres rabbitmq redis

echo "  [2/2] 等待 PostgreSQL 就绪..."
until docker compose exec -T postgres pg_isready -U postgres -d agentx >/dev/null 2>&1; do
    sleep 1
done

echo ""
echo "  ✅ 基础设施就绪，启动后端（热重载模式）..."
echo "  提示：修改代码保存后自动重启，Ctrl+C 停止"
echo ""

cd "$(dirname "$0")/../AgentX"
./mvnw spring-boot:run

echo ""
echo "  后端已停止。基础设施仍在后台运行。"
echo "  如需停止基础设施：cd deploy && docker compose stop"
