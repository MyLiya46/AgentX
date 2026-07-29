<#
.SYNOPSIS
    AgentX 快速开发启动脚本 (PowerShell)
.DESCRIPTION
    Docker 仅启动基础设施（postgres + rabbitmq + redis），
    后端在本地用 mvn spring-boot:run 运行，支持热重载。
    与完整 Docker 部署方案共存，互不影响。
.NOTES
    完整方案：docker compose --profile local up -d
    快速方案：.\start-dev-fast.ps1
#>

$ErrorActionPreference = "Stop"

# 设置控制台为 UTF-8 编码，解决 Java 中文日志乱码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host ""
Write-Host "  AgentX 快速开发模式（后端热重载）" -ForegroundColor Cyan
Write-Host "  Docker 仅运行数据库/消息队列，后端本地运行" -ForegroundColor Cyan
Write-Host ""

# 检查 Docker 环境
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "  [失败] Docker 未安装，请先安装 Docker Desktop" -ForegroundColor Red
    pause
    exit 1
}

# 切换到脚本所在目录（deploy/）
Set-Location $PSScriptRoot

Write-Host "  [1/2] 启动基础设施服务（postgres + rabbitmq + redis）..." -ForegroundColor Yellow
docker compose --profile local up -d postgres rabbitmq redis
if ($LASTEXITCODE -ne 0) {
    Write-Host "  [失败] 基础设施启动失败" -ForegroundColor Red
    pause
    exit 1
}

Write-Host "  [2/2] 等待 PostgreSQL 就绪..." -ForegroundColor Yellow
$maxWait = 30
for ($i = 0; $i -lt $maxWait; $i++) {
    $result = docker compose exec -T postgres pg_isready -U postgres -d agentx 2>&1
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep -Seconds 1
}

Write-Host ""
Write-Host "  基础设施就绪，启动后端（热重载模式）..." -ForegroundColor Green
Write-Host "  提示：修改代码保存后自动重启，Ctrl+C 停止" -ForegroundColor Green
Write-Host ""
Write-Host "  前端开发请另开终端执行：" -ForegroundColor Cyan
Write-Host "    cd agentx-frontend-plus; npm run dev" -ForegroundColor White
Write-Host ""

# 启动后端
Set-Location "$PSScriptRoot\..\AgentX"

# Windows Docker Desktop 使用命名管道，而非 Unix socket
$env:AGENTX_CONTAINER_DOCKER_HOST = "npipe:////./pipe/docker_engine"

& .\mvnw.cmd spring-boot:run

Write-Host ""
Write-Host "  后端已停止。基础设施仍在后台运行。" -ForegroundColor Yellow
Write-Host "  如需停止基础设施：cd deploy; docker compose stop" -ForegroundColor Yellow
pause
