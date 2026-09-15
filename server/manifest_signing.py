"""Persistent Ed25519 signing for the versioned update-manifest protocol."""

import base64
import hashlib
import json
import os
import tempfile
import threading
import time

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


SIGNATURE_TTL_SECONDS = int(os.environ.get('MANIFEST_SIGNATURE_TTL_SECONDS',
                                           str(7 * 24 * 60 * 60)))
MAX_SIGNATURE_TTL_SECONDS = 31 * 24 * 60 * 60
_KEY_LOCK = threading.Lock()


def _canonical_json(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def _write_bytes_atomically(path, value, mode):
    directory = os.path.dirname(path)
    os.makedirs(directory, mode=0o700, exist_ok=True)
    os.chmod(directory, 0o700)
    descriptor, temporary = tempfile.mkstemp(prefix='.manifest-key-', suffix='.tmp', dir=directory)
    try:
        os.fchmod(descriptor, mode)
        with os.fdopen(descriptor, 'wb') as output:
            output.write(value)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        os.chmod(path, mode)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _write_json_atomically(path, value):
    descriptor, temporary = tempfile.mkstemp(prefix='.manifest-signature-', suffix='.tmp',
                                            dir=os.path.dirname(path))
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
            json.dump(value, output, sort_keys=True, separators=(',', ':'), ensure_ascii=True)
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _key_paths(data_dir):
    key_dir = os.path.join(data_dir, 'manifest-keys')
    return (key_dir, os.path.join(key_dir, 'manifest-signing-private.pem'),
            os.path.join(key_dir, 'manifest-signing-public.pem'),
            os.path.join(key_dir, 'manifest-signing-public.der.base64'))


def _key_material(data_dir, logger):
    """Return the persistent key; never overwrite a missing, corrupt, or mismatched key."""
    with _KEY_LOCK:
        key_dir, private_path, public_path, public_b64_path = _key_paths(data_dir)
        os.makedirs(key_dir, mode=0o700, exist_ok=True)
        os.chmod(key_dir, 0o700)
        private_exists = os.path.exists(private_path)
        public_exists = os.path.exists(public_path)
        if not private_exists and public_exists:
            raise RuntimeError('manifest public key exists but private signing key is missing')
        if not private_exists:
            private_key = Ed25519PrivateKey.generate()
            _write_bytes_atomically(private_path, private_key.private_bytes(
                serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption()), 0o600)
            logger.info('Generated Ed25519 manifest signing key in %s', key_dir)
        else:
            try:
                with open(private_path, 'rb') as source:
                    private_key = serialization.load_pem_private_key(source.read(), password=None)
            except Exception as error:
                raise RuntimeError('cannot load manifest private signing key') from error
            if not isinstance(private_key, Ed25519PrivateKey):
                raise RuntimeError('manifest private signing key must be Ed25519')
            os.chmod(private_path, 0o600)

        public_key = private_key.public_key()
        public_pem = public_key.public_bytes(serialization.Encoding.PEM,
                                             serialization.PublicFormat.SubjectPublicKeyInfo)
        public_der = public_key.public_bytes(serialization.Encoding.DER,
                                             serialization.PublicFormat.SubjectPublicKeyInfo)
        if public_exists:
            try:
                with open(public_path, 'rb') as source:
                    stored_public = serialization.load_pem_public_key(source.read())
                stored_der = stored_public.public_bytes(serialization.Encoding.DER,
                                                        serialization.PublicFormat.SubjectPublicKeyInfo)
            except Exception as error:
                raise RuntimeError('cannot load manifest public signing key') from error
            if stored_der != public_der:
                raise RuntimeError('manifest public signing key does not match private signing key')
            os.chmod(public_path, 0o644)
        else:
            _write_bytes_atomically(public_path, public_pem, 0o644)
        _write_bytes_atomically(public_b64_path, base64.b64encode(public_der) + b'\n', 0o644)
        return private_key, public_der, 'ed25519-' + hashlib.sha256(public_der).hexdigest()[:16]


def public_key_descriptor(data_dir, logger):
    _private, public_der, key_id = _key_material(data_dir, logger)
    return {'algorithm': 'Ed25519', 'key_id': key_id,
            'public_key': base64.b64encode(public_der).decode('ascii')}


def sign_manifest(data_dir, manifest, logger):
    if SIGNATURE_TTL_SECONDS <= 0 or SIGNATURE_TTL_SECONDS > MAX_SIGNATURE_TTL_SECONDS:
        raise RuntimeError('MANIFEST_SIGNATURE_TTL_SECONDS must be between 1 and 2678400')
    private_key, _public_der, key_id = _key_material(data_dir, logger)
    canonical_manifest = _canonical_json(manifest)
    now = int(time.time())
    payload = _canonical_json({
        'format': 'mc-update-manifest-v1',
        'issued_at': now,
        'expires_at': now + SIGNATURE_TTL_SECONDS,
        'manifest_sha256': hashlib.sha256(canonical_manifest).hexdigest(),
        'manifest': manifest,
    })
    envelope = {
        'format': 'mc-update-signed-manifest-v1',
        'algorithm': 'Ed25519',
        'key_id': key_id,
        'payload': base64.b64encode(payload).decode('ascii'),
        'signature': base64.b64encode(private_key.sign(payload)).decode('ascii'),
    }
    _write_json_atomically(os.path.join(data_dir, 'manifest-signature.json'), envelope)
    return envelope
