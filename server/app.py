#!/usr/bin/env python3
"""
Minecraft Auto-Update Service - HTTP API Server
Provides manifest queries, file downloads, and more.
"""

import os
import sys
import json
import hashlib
import logging
import mimetypes
import re
import subprocess
import tempfile
import zipfile

from urllib.parse import quote

from flask import Flask, jsonify, send_file, abort, request

# Configuration
DATA_DIR = os.environ.get('DATA_DIR', '/data')
FILES_DIR = os.path.join(DATA_DIR, 'files')
LOGS_DIR = os.path.join(DATA_DIR, 'logs')
AGENT_DIR = os.path.join(DATA_DIR, 'agent')
MANIFEST_PATH = os.path.join(DATA_DIR, 'manifest.json')
CONFIG_PATH = os.path.join(DATA_DIR, 'update-config.json')
GUI_PRESETS_DIR = os.path.join(DATA_DIR, 'gui-presets')
GUI_PRESET_CONFIG_PATH = os.path.join(DATA_DIR, 'gui-preset.json')

_SAFE_GUI_ID = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')
_SAFE_GUI_ARCHIVE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.jar$')
_GENERATION_TARGETS = frozenset(('manifest', 'gui-preset'))

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


def _sha256(filepath):
    digest = hashlib.sha256()
    with open(filepath, 'rb') as source:
        for chunk in iter(lambda: source.read(8192), b''):
            digest.update(chunk)
    return digest.hexdigest()


def _load_update_config():
    """Load operator configuration, using an empty object when it is absent."""
    if not os.path.isfile(CONFIG_PATH):
        return {}
    try:
        with open(CONFIG_PATH, 'r', encoding='utf-8') as source:
            value = json.load(source)
    except (json.JSONDecodeError, OSError) as error:
        raise ValueError(f'failed to load update-config.json: {error}')
    if not isinstance(value, dict):
        raise ValueError('update-config.json must contain a JSON object')
    return value


def _validate_gui_preset_source(value):
    """Validate the administrator-maintained preset declaration."""
    if not isinstance(value, dict):
        raise ValueError('generation.gui_preset must be an object')
    preset_id = value.get('id')
    version = value.get('version')
    filename = value.get('file')
    if not all(isinstance(item, str) and item for item in
               (preset_id, version, filename)):
        raise ValueError('generation.gui_preset requires id, version, and file')
    if (not _SAFE_GUI_ID.fullmatch(preset_id)
            or not _SAFE_GUI_ARCHIVE.fullmatch(filename)
            or len(version) > 128
            or version != version.strip()
            or any(ord(character) < 32 or ord(character) == 127
                   for character in version)):
        raise ValueError('generation.gui_preset contains an invalid id, version, or archive name')
    return preset_id, version, filename


def _load_generation_configuration():
    """Read server-only generation targets from update-config.json."""
    config = _load_update_config()
    generation = config.get('generation')
    if generation is None:
        return ['manifest'], None
    if not isinstance(generation, dict):
        raise ValueError('generation must be an object')
    targets = generation.get('targets', ['manifest'])
    if (not isinstance(targets, list) or not targets
            or any(not isinstance(target, str) or target not in _GENERATION_TARGETS
                   for target in targets)):
        raise ValueError('generation.targets must be a non-empty list of manifest and/or gui-preset')
    if len(set(targets)) != len(targets):
        raise ValueError('generation.targets must not contain duplicates')
    preset = generation.get('gui_preset')
    if 'gui-preset' in targets:
        _validate_gui_preset_source(preset)
    return targets, preset


def _build_gui_preset_descriptor(preset_source):
    """Build a descriptor from a manually installed archive without publishing it."""
    preset_id, version, filename = _validate_gui_preset_source(preset_source)
    archive = os.path.join(GUI_PRESETS_DIR, filename)
    if not os.path.isfile(archive):
        raise ValueError(f'preset archive is missing: {filename}')
    if not zipfile.is_zipfile(archive):
        raise ValueError(f'preset archive is not a valid JAR/ZIP file: {filename}')
    return {
        'id': preset_id,
        'version': version,
        'file': filename,
        'path': '/api/v2/gui-presets/' + quote(filename, safe=''),
        'sha256': _sha256(archive),
        'size': os.path.getsize(archive),
    }


def _write_json_atomically(path, value):
    """Replace one JSON document without exposing a partial descriptor."""
    directory = os.path.dirname(path)
    os.makedirs(directory, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.gui-preset-', suffix='.tmp', dir=directory)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
            json.dump(value, output, indent=2, ensure_ascii=True)
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _load_gui_preset_offer():
    """Load the published descriptor and verify its archive remains unchanged."""
    if not os.path.isfile(GUI_PRESET_CONFIG_PATH):
        return None
    try:
        with open(GUI_PRESET_CONFIG_PATH, 'r', encoding='utf-8') as source:
            descriptor = json.load(source)
        preset_id, version, filename = _validate_gui_preset_source(descriptor)
        path = descriptor.get('path')
        expected_path = '/api/v2/gui-presets/' + quote(filename, safe='')
        archive_hash = descriptor.get('sha256')
        size = descriptor.get('size')
        if (path != expected_path or not isinstance(archive_hash, str)
                or not re.fullmatch(r'[0-9A-Fa-f]{64}', archive_hash)
                or not isinstance(size, int) or size < 0):
            raise ValueError('published descriptor is invalid')
        archive = os.path.join(GUI_PRESETS_DIR, filename)
        if (not os.path.isfile(archive) or os.path.getsize(archive) != size
                or _sha256(archive) != archive_hash.lower()):
            raise ValueError(f'published preset archive no longer matches: {filename}')
        return {
            'id': preset_id,
            'version': version,
            'path': path,
            'sha256': archive_hash.lower(),
            'size': size,
        }
    except (json.JSONDecodeError, OSError, ValueError) as error:
        logger.error(f"Failed to load GUI preset offer: {error}")
        return None

@app.route('/api/manifest', methods=['GET'])
def api_manifest_v1():
    """Deprecated v1 manifest endpoint — returns 410 Gone."""
    logger.warning("Deprecated /api/manifest accessed — returning 410 Gone")
    return jsonify({
        'error': 'Gone',
        'message': 'This API version is no longer available. Please upgrade your client to continue using.'
    }), 410


# ═══════════════════════════════════════════════════════════════════
#  API Endpoints
# ═══════════════════════════════════════════════════════════════════

@app.route('/api/v2/manifest', methods=['GET'])
def api_manifest():
    """Return the full file manifest (paths, hashes, sizes)."""
    manifest = _load_manifest()
    if manifest is None:
        return jsonify({'error': 'manifest not available'}), 503
    return jsonify(manifest)


@app.route('/api/v2/gui-preset', methods=['GET'])
def api_gui_preset():
    """Return one optional server GUI-preset descriptor."""
    offer = _load_gui_preset_offer()
    if offer is None:
        abort(404)
    response = jsonify(offer)
    response.headers['Cache-Control'] = 'no-store'
    return response


@app.route('/api/v2/gui-presets/<filename>', methods=['GET'])
def api_gui_preset_download(filename):
    """Download one direct-child GUI preset archive named by the descriptor."""
    if not _SAFE_GUI_ARCHIVE.fullmatch(filename):
        abort(404)
    archive = os.path.join(GUI_PRESETS_DIR, filename)
    if not os.path.isfile(archive):
        abort(404)
    logger.info(f"GUI preset download: {filename}")
    return send_file(archive, mimetype='application/java-archive', as_attachment=False)


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
    """Download the latest UpdateAgent_core.jar (for self-update)."""
    agent_jar = os.path.join(AGENT_DIR, 'UpdateAgent_core.jar')
    if not os.path.isfile(agent_jar):
        logger.warning("Agent core JAR not found")
        abort(404)
    logger.info("Agent core download")
    return send_file(agent_jar, mimetype='application/java-archive', as_attachment=False)


@app.route('/api/config', methods=['GET'])
def api_config():
    """Return only client-facing update configuration."""
    try:
        config = _load_update_config()
    except ValueError as error:
        logger.error(f"Failed to load update config: {error}")
        return jsonify({'error': 'config not available'}), 503
    return jsonify({
        'managed_paths': config.get('managed_paths', ['*']),
        'excluded_paths': config.get('excluded_paths', []),
    })

@app.route('/api/generate', methods=['POST'])
def api_generate():
    """Generate the configured server targets from update-config.json."""
    # Preserve the existing optional token protection for generation triggers.
    token = os.environ.get('GENERATE_TOKEN', '')
    if token:
        req_token = request.headers.get('X-Generate-Token', '')
        if req_token != token:
            logger.warning("Generate attempt with invalid token")
            return jsonify({'error': 'unauthorized'}), 401

    try:
        targets, preset_source = _load_generation_configuration()
        descriptor = (_build_gui_preset_descriptor(preset_source)
                      if 'gui-preset' in targets else None)
    except ValueError as error:
        logger.warning("Generate request rejected: %s", error)
        return jsonify({'error': str(error)}), 400

    output = ''
    if 'manifest' in targets:
        cmd = [sys.executable, '/app/generate_manifest.py',
               '--dir', FILES_DIR, '--out', DATA_DIR]
        agent_jar = os.path.join(AGENT_DIR, 'UpdateAgent_core.jar')
        if os.path.isfile(agent_jar):
            cmd.extend(['--agent-jar', agent_jar])
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        except subprocess.TimeoutExpired:
            return jsonify({'error': 'manifest generation timed out'}), 504
        except OSError as error:
            return jsonify({'error': str(error)}), 500
        output = result.stdout
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

    if descriptor is not None:
        try:
            _write_json_atomically(GUI_PRESET_CONFIG_PATH, descriptor)
        except OSError as error:
            logger.error("Failed to publish GUI preset: %s", error)
            return jsonify({'error': 'failed to publish GUI preset'}), 500
        logger.info("Published GUI preset: %s (%s)",
                    descriptor['id'], descriptor['version'])

    logger.info("Generation triggered via API: %s", ', '.join(targets))
    response = {'status': 'ok', 'targets': targets, 'output': output}
    if descriptor is not None:
        response['gui_preset'] = {
            key: descriptor[key] for key in ('id', 'version', 'path', 'sha256', 'size')
        }
    return jsonify(response)

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
