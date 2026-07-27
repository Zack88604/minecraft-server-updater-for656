#!/usr/bin/env python3
"""
Minecraft Auto-Update Service - HTTP API Server
Provides manifest queries, file downloads, and more.
"""

import os
import sys
import json
import logging
import mimetypes
import subprocess

from flask import Flask, jsonify, send_file, abort, request

# Configuration
DATA_DIR = os.environ.get('DATA_DIR', '/data')
FILES_DIR = os.path.join(DATA_DIR, 'files')
LOGS_DIR = os.path.join(DATA_DIR, 'logs')
AGENT_DIR = os.path.join(DATA_DIR, 'agent')
MANIFEST_PATH = os.path.join(DATA_DIR, 'manifest.json')
CONFIG_PATH = os.path.join(DATA_DIR, 'update-config.json')

app = Flask(__name__)

# ── Logging: tee all console output to log file ──────────────────
STARTUP_TS = os.environ.get('STARTUP_TS', 'unknown')
LOG_FILE = os.path.join(LOGS_DIR, f'{STARTUP_TS}.log')

# Ensure log directory exists
os.makedirs(LOGS_DIR, exist_ok=True)

# Save original stdout/stderr for console output
_original_stdout = sys.stdout
_original_stderr = sys.stderr

# Open log file (line-buffered, each write flushed immediately)
_log_fh = open(LOG_FILE, 'a', encoding='utf-8', buffering=1)


class _TeeWriter:
    """Tee writer: writes to both the original stream and the log file."""

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


# Redirect stdout/stderr so print(), subprocess output, Flask/Werkzeug logs
# all go to both console and the log file.
sys.stdout = _TeeWriter(_original_stdout, _log_fh)
sys.stderr = _TeeWriter(_original_stderr, _log_fh)

# Configure logger (StreamHandler writes to redirected stdout, reaching both console and file)
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
    """Load manifest.json; return None if not available."""
    if not os.path.isfile(MANIFEST_PATH):
        return None
    try:
        with open(MANIFEST_PATH, 'r', encoding='utf-8') as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError) as e:
        logger.error(f"Failed to load manifest: {e}")
        return None


# ═══════════════════════════════════════════════════════════════════
#  API Endpoints
# ═══════════════════════════════════════════════════════════════════

@app.route('/api/manifest', methods=['GET'])
def api_manifest():
    """Return the full file manifest (paths, hashes, sizes)."""
    manifest = _load_manifest()
    if manifest is None:
        return jsonify({'error': 'manifest not available'}), 503
    return jsonify(manifest)


@app.route('/api/files/<path:filepath>', methods=['GET'])
def api_download(filepath):
    """Download a single resource file."""
    safe_path = os.path.normpath(filepath).lstrip('/')
    full_path = os.path.join(FILES_DIR, safe_path)

    # Prevent path traversal outside FILES_DIR
    if not os.path.realpath(full_path).startswith(os.path.realpath(FILES_DIR)):
        logger.warning(f"Path traversal attempt: {filepath}")
        abort(403)

    if not os.path.isfile(full_path):
        logger.warning(f"File not found: {filepath}")
        abort(404)

    mimetype, _ = mimetypes.guess_type(full_path)
    logger.info(f"Download: {filepath}")
    return send_file(full_path, mimetype=mimetype, as_attachment=False)


@app.route('/api/agent', methods=['GET'])
def api_agent():
    """Download the latest UpdateAgent.jar (for self-update)."""
    agent_jar = os.path.join(AGENT_DIR, 'UpdateAgent.jar')
    if not os.path.isfile(agent_jar):
        logger.warning("Agent JAR not found")
        abort(404)
    logger.info("Agent download")
    return send_file(agent_jar, mimetype='application/java-archive', as_attachment=False)


@app.route('/api/config', methods=['GET'])
def api_config():
    """Return update configuration (managed_paths, excluded_paths, etc.)."""
    if not os.path.isfile(CONFIG_PATH):
        # Fall back to manifest if config file does not exist (backward compat)
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
    """Trigger manifest generation via HTTP (no docker exec needed)."""
    # Simple token protection against unauthorized triggers
    token = os.environ.get('GENERATE_TOKEN', '')
    if token:
        req_token = request.headers.get('X-Generate-Token', '')
        if req_token != token:
            logger.warning("Generate attempt with invalid token")
            return jsonify({'error': 'unauthorized'}), 401

    cmd = [sys.executable, '/app/generate_manifest.py',
           '--dir', FILES_DIR, '--out', DATA_DIR]

    # Attach agent JAR info if present, for client self-update
    agent_jar = os.path.join(AGENT_DIR, 'UpdateAgent.jar')
    if os.path.isfile(agent_jar):
        cmd.extend(['--agent-jar', agent_jar])

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
    """Health check."""
    manifest_ok = _load_manifest() is not None
    status = 200 if manifest_ok else 503
    return jsonify({
        'status': 'ok' if status == 200 else 'degraded',
        'manifest': manifest_ok,
    }), status


# ═══════════════════════════════════════════════════════════════════
#  Entry Point
# ═══════════════════════════════════════════════════════════════════

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 25565))
    host = os.environ.get('HOST', '0.0.0.0')
    debug = os.environ.get('DEBUG', '').lower() in ('1', 'true', 'yes')

    logger.info(f"Starting update service on {host}:{port}")
    logger.info(f"Data directory: {DATA_DIR}")
    logger.info(f"Files directory: {FILES_DIR}")
    logger.info(f"Agent directory: {AGENT_DIR}")

    # Pre-start integrity check
    manifest = _load_manifest()
    if manifest is None:
        logger.warning("Manifest not found. Run 'generate-manifest' first.")
    else:
        logger.info(f"Manifest loaded, files count: {len(manifest.get('files', []))}")

    app.run(host=host, port=port, debug=debug)
