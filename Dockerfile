# ── Minecraft 自动更新服务 Docker 镜像 ────────────────────────────
# 构建（在项目根目录执行）：
#   docker build -t mc-update-service -f 656-auto-update/Dockerfile .
# 运行：
#   docker run -d -p 25565:25565 -v /path/to/files:/data/files --name mc-update mc-update-service
# 生成清单：
#   docker exec mc-update python3 /app/generate_manifest.py "1.0" --dir /data/files --out /data
# ──────────────────────────────────────────────────────────────────

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/gcr.io/iguazio/alpine:3.20

# 安装 Python 3 和 Flask（通过 apk 安装，避免 PEP 668 限制）
RUN apk add --no-cache python3 py3-flask

# 复制服务端代码
COPY 656-auto-update/server/ /app/

# 创建数据目录结构
RUN mkdir -p /data/files

# 赋予 entrypoint 执行权限
RUN chmod +x /app/entrypoint.sh

# 开放端口（服务端默认 25565，与 Minecraft 默认端口一致便于记忆）
EXPOSE 25565

# 设置工作目录
WORKDIR /data

# 容器启动入口
ENTRYPOINT ["/app/entrypoint.sh"]
