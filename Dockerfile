# ── Minecraft Auto-Update Service Docker Image ────────────────────
# Build (run from project root):
#   docker build -t mc-update-service -f 656-auto-update/Dockerfile .
# Run:
#   docker run -d -p 25565:25565 -v /path/to/files:/data/files --name mc-update mc-update-service
# Generate manifest:
#   docker exec mc-update python3 /app/generate_manifest.py "1.0" --dir /data/files --out /data
# ──────────────────────────────────────────────────────────────────

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/gcr.io/iguazio/alpine:3.20

# Install Python 3 and Flask (via apk to avoid PEP 668 restrictions)
RUN apk add --no-cache python3 py3-flask

# Copy server code
COPY 656-auto-update/server/ /app/

# Create data directory structure
RUN mkdir -p /data/files /data/agent

# Make entrypoint executable
RUN chmod +x /app/entrypoint.sh

# Expose port (default 25565, matching Minecraft default for easy recall)
EXPOSE 25565

# Set working directory
WORKDIR /data

# Container entrypoint
ENTRYPOINT ["/app/entrypoint.sh"]
