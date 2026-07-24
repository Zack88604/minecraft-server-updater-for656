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
CONFIG_PATH = os.path.join(DATA_DIR, 'update-config.json')

app = Flask(__name__)

# ── 日志配置：所有控制台输出同步写入日志文件 ──────────────────────
STARTUP_TS = os.environ.get('STARTUP_TS', 'unknown')
LOG_FILE = os.path.join(LOGS_DIR, f'{STARTUP_TS}.log')

# 确保日志目录存在
os.makedirs(LOGS_DIR, exist_ok=True)

# 保存原始 stdout/stderr（用于控制台真实输出）
_original_stdout = sys.stdout
_original_stderr = sys.stderr

# 打开日志文件（行缓冲模式，每条写入立即刷盘）
_log_fh = open(LOG_FILE, 'a', encoding='utf-8', buffering=1)


class _TeeWriter:
    """同时写入原始流和日志文件的包装器 — 所有 console 输出都会被记录"""

    def __init__(self, original, log_fh):
        self._original = original
        self._log_fh = log_fh

    def write(self, message):
        self._original.write(message)
        self._log_fh.write(message)

    def flush(self):
        self._original.flush()
        self._log_fh.flush()

    def isatty(self):
        return self._original.isatty()

    def fileno(self):
        return self._original.fileno()


# 重定向 stdout/stderr：print()、subprocess 输出、Flask/Werkzeug 日志
# 等所有控制台内容都会同步写入日志文件
sys.stdout = _TeeWriter(_original_stdout, _log_fh)
sys.stderr = _TeeWriter(_original_stderr, _log_fh)

# 配置 logger（StreamHandler 输出到重定向后的 stdout，会同时到达控制台和日志文件）
logger = logging.getLogger('update-service')
logger.setLevel(logging.INFO)
formatter = logging.Formatter(
    '[update-service] %(asctime)s %(levelname)s %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S',
)

stream_handler = logging.StreamHandler(sys.stdout)
stream_handler.setFormatter(formatter)
logger.addHandler(stream_handler)


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


# ═══════════════════════════════════════════════════════════════════
#  API 端点
# ═══════════════════════════════════════════════════════════════════

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

    cmd = [sys.executable, '/app/generate_manifest.py',
           '--dir', FILES_DIR, '--out', DATA_DIR]

    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        logger.info("Generate manifest triggered via API")
        if result.stdout:
            logger.info("Generate stdout:\n%s", result.stdout.rstrip())
        if result.returncode != 0:
            if result.stderr:
                logger.error("Generate stderr:\n%s", result.stderr.rstrip())
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
    status = 200 if manifest_ok else 503
    return jsonify({
        'status': 'ok' if status == 200 else 'degraded',
        'manifest': manifest_ok,
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
    manifest = _load_manifest()
    if manifest is None:
        logger.warning("Manifest not found. Run 'generate-manifest' first.")
    else:
        logger.info(f"Manifest loaded, files count: {len(manifest.get('files', []))}")

    app.run(host=host, port=port, debug=debug)
