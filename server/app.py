#!/usr/bin/env python3
"""
Minecraft 自动更新服务 - HTTP API 服务器
提供版本查询、资源清单查询、文件下载等接口
"""

import os
import sys
import json
import logging
import mimetypes
import subprocess

from flask import Flask, jsonify, send_file, abort, request

# 配置
DATA_DIR = os.environ.get('DATA_DIR', '/data')
FILES_DIR = os.path.join(DATA_DIR, 'files')
LOGS_DIR = os.path.join(DATA_DIR, 'logs')
MANIFEST_PATH = os.path.join(DATA_DIR, 'manifest.json')
VERSION_PATH = os.path.join(DATA_DIR, 'version.txt')
CONFIG_PATH = os.path.join(DATA_DIR, 'update-config.json')

app = Flask(__name__)

# ── 日志配置：stdout + 文件持久化 ──────────────────────────────────
STARTUP_TS = os.environ.get('STARTUP_TS', 'unknown')
LOG_FILE = os.path.join(LOGS_DIR, f'{STARTUP_TS}.log')

# 确保日志目录存在
os.makedirs(LOGS_DIR, exist_ok=True)

logger = logging.getLogger('update-service')
logger.setLevel(logging.INFO)
formatter = logging.Formatter(
    '[update-service] %(asctime)s %(levelname)s %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S',
)

# stdout handler
stream_handler = logging.StreamHandler(sys.stdout)
stream_handler.setFormatter(formatter)
logger.addHandler(stream_handler)

# file handler
file_handler = logging.FileHandler(LOG_FILE, encoding='utf-8')
file_handler.setFormatter(formatter)
logger.addHandler(file_handler)


def _load_manifest():
    """加载 manifest.json，若不存在则返回 None"""
    if not os.path.isfile(MANIFEST_PATH):
        return None
    try:
        with open(MANIFEST_PATH, 'r', encoding='utf-8') as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError) as e:
        logger.error(f"Failed to load manifest: {e}")
        return None


def _load_version():
    """加载 version.txt，若不存在则返回 None"""
    if not os.path.isfile(VERSION_PATH):
        return None
    try:
        with open(VERSION_PATH, 'r', encoding='utf-8') as f:
            return f.read().strip()
    except OSError as e:
        logger.error(f"Failed to load version: {e}")
        return None


# ═══════════════════════════════════════════════════════════════════
#  API 端点
# ═══════════════════════════════════════════════════════════════════

@app.route('/api/version', methods=['GET'])
def api_version():
    """返回当前版本号"""
    version = _load_version()
    if version is None:
        return jsonify({'error': 'version not available'}), 503
    return jsonify({'version': version})


@app.route('/api/manifest', methods=['GET'])
def api_manifest():
    """返回完整的资源清单（包含所有文件路径、hash、大小）"""
    manifest = _load_manifest()
    if manifest is None:
        return jsonify({'error': 'manifest not available'}), 503
    return jsonify(manifest)


@app.route('/api/files/<path:filepath>', methods=['GET'])
def api_download(filepath):
    """下载单个资源文件"""
    safe_path = os.path.normpath(filepath).lstrip('/')
    full_path = os.path.join(FILES_DIR, safe_path)

    # 确保路径没有逃逸到 FILES_DIR 之外
    if not os.path.realpath(full_path).startswith(os.path.realpath(FILES_DIR)):
        logger.warning(f"Path traversal attempt: {filepath}")
        abort(403)

    if not os.path.isfile(full_path):
        logger.warning(f"File not found: {filepath}")
        abort(404)

    mimetype, _ = mimetypes.guess_type(full_path)
    logger.info(f"Download: {filepath}")
    return send_file(full_path, mimetype=mimetype, as_attachment=False)


@app.route('/api/config', methods=['GET'])
def api_config():
    """返回更新配置（managed_paths, excluded_paths 等）"""
    if not os.path.isfile(CONFIG_PATH):
        # 如果配置文件不存在，从 manifest 中读取（兼容旧 manifest）
        manifest = _load_manifest()
        if manifest:
            return jsonify({
                'managed_paths': manifest.get('managed_paths', ['*']),
                'excluded_paths': manifest.get('excluded_paths', []),
            })
        return jsonify({'managed_paths': ['*'], 'excluded_paths': []})
    try:
        with open(CONFIG_PATH, 'r', encoding='utf-8') as f:
            config = json.load(f)
        return jsonify(config)
    except (json.JSONDecodeError, OSError) as e:
        logger.error(f"Failed to load update config: {e}")
        return jsonify({'error': 'config not available'}), 503


@app.route('/api/generate', methods=['POST'])
def api_generate():
    """通过 HTTP 触发生成 manifest（无需 docker exec）"""
    # 简单的 token 保护，防止随意触发
    token = os.environ.get('GENERATE_TOKEN', '')
    if token:
        req_token = request.headers.get('X-Generate-Token', '')
        if req_token != token:
            logger.warning("Generate attempt with invalid token")
            return jsonify({'error': 'unauthorized'}), 401

    version = request.args.get('version', None)
    cmd = [sys.executable, '/app/generate_manifest.py',
           '--dir', FILES_DIR, '--out', DATA_DIR]
    if version:
        cmd.append(version)

    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        logger.info(f"Generate manifest: version={version or 'auto'}")
        if result.returncode != 0:
            logger.error(f"Generate failed: {result.stderr}")
            return jsonify({
                'status': 'error',
                'output': result.stdout,
                'error': result.stderr,
            }), 500
        return jsonify({
            'status': 'ok',
            'output': result.stdout,
        })
    except subprocess.TimeoutExpired:
        return jsonify({'error': 'timeout'}), 504
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/api/health', methods=['GET'])
def api_health():
    """健康检查"""
    manifest_ok = _load_manifest() is not None
    version_ok = _load_version() is not None
    status = 200 if (manifest_ok and version_ok) else 503
    return jsonify({
        'status': 'ok' if status == 200 else 'degraded',
        'manifest': manifest_ok,
        'version': version_ok,
    }), status


# ═══════════════════════════════════════════════════════════════════
#  启动入口
# ═══════════════════════════════════════════════════════════════════

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 25565))
    host = os.environ.get('HOST', '0.0.0.0')
    debug = os.environ.get('DEBUG', '').lower() in ('1', 'true', 'yes')

    logger.info(f"Starting update service on {host}:{port}")
    logger.info(f"Data directory: {DATA_DIR}")
    logger.info(f"Files directory: {FILES_DIR}")

    # 启动前检查数据完整性
    version = _load_version()
    manifest = _load_manifest()
    if version is None or manifest is None:
        logger.warning("Manifest or version not found. Run 'generate-manifest' first.")
    else:
        logger.info(f"Current version: {version}, files count: {len(manifest.get('files', []))}")

    app.run(host=host, port=port, debug=debug)
