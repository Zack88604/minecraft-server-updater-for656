#!/usr/bin/env python3
"""Create the signed descriptor consumed by the server GUI-preset endpoint."""

import argparse
import base64
import hashlib
import json
import re
import sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


SAFE_ID = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')
SAFE_ARCHIVE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.jar$')
DOWNLOAD_PREFIX = '/api/v2/gui-presets/'


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(8192), b''):
            digest.update(chunk)
    return digest.hexdigest()


def validate(value, name):
    if not isinstance(value, str) or not SAFE_ID.fullmatch(value):
        raise ValueError(f'{name} must match {SAFE_ID.pattern}')
    return value


def canonical_payload(preset_id, version, download_path, archive_hash, size, key_id):
    return ('\n'.join((preset_id, version, download_path, archive_hash,
                       str(size), key_id))).encode('utf-8')


def main():
    parser = argparse.ArgumentParser(
        description='Sign one server-published Minecraft updater GUI preset.')
    parser.add_argument('--preset', required=True, type=Path,
                        help='Preset JAR placed in /data/gui-presets/')
    parser.add_argument('--id', required=True, help='Stable preset identifier')
    parser.add_argument('--version', required=True, help='Preset version')
    parser.add_argument('--key-id', required=True, help='Pinned signing-key identifier')
    parser.add_argument('--private-key', required=True, type=Path,
                        help='Ed25519 private key in PEM format')
    parser.add_argument('--out', type=Path,
                        help='Write gui-preset.json here (otherwise print JSON)')
    parser.add_argument('--public-key-out', type=Path,
                        help='Optionally write Base64 X.509 public key for client setup')
    args = parser.parse_args()

    preset = args.preset.resolve()
    if not preset.is_file() or not SAFE_ARCHIVE.fullmatch(preset.name):
        parser.error('preset must be a direct, safe .jar filename')
    try:
        preset_id = validate(args.id, 'id')
        key_id = validate(args.key_id, 'key-id')
    except ValueError as error:
        parser.error(str(error))
    if not args.version or len(args.version) > 128 or '\r' in args.version or '\n' in args.version:
        parser.error('version must be non-empty, at most 128 characters, and single-line')

    try:
        private_key = serialization.load_pem_private_key(
            args.private_key.read_bytes(), password=None)
    except (OSError, ValueError) as error:
        parser.error(f'cannot read private key: {error}')
    if not isinstance(private_key, Ed25519PrivateKey):
        parser.error('private-key must be an Ed25519 PEM key')

    archive_hash = sha256(preset)
    archive_size = preset.stat().st_size
    download_path = DOWNLOAD_PREFIX + preset.name
    signature = private_key.sign(canonical_payload(
        preset_id, args.version, download_path, archive_hash, archive_size, key_id))

    descriptor = {
        'id': preset_id,
        'version': args.version,
        'file': preset.name,
        'key_id': key_id,
        'signature': base64.b64encode(signature).decode('ascii'),
    }
    encoded_descriptor = json.dumps(descriptor, indent=2, ensure_ascii=True) + '\n'
    if args.out:
        args.out.write_text(encoded_descriptor, encoding='utf-8')
    else:
        sys.stdout.write(encoded_descriptor)

    if args.public_key_out:
        public_bytes = private_key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo)
        args.public_key_out.write_text(
            base64.b64encode(public_bytes).decode('ascii') + '\n', encoding='ascii')


if __name__ == '__main__':
    main()
