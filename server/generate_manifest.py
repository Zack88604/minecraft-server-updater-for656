#!/usr/bin/env python3
"""
Minecraft Auto-Update Service - Manifest Generator
Run manually after updating resources to scan the file directory and generate
manifest.json.

Usage:
    python generate_manifest.py [--dir <files_dir>] [--out <output_dir>]

    Default files dir: /data/files, output dir: /data.
"""

import sys
import os
import json
import hashlib
import argparse


def compute_sha256(filepath):
    """Compute the SHA-256 hash of a file."""
    h = hashlib.sha256()
    with open(filepath, 'rb') as f:
        # Read in chunks to avoid loading large files into memory
        for chunk in iter(lambda: f.read(8192), b''):
            h.update(chunk)
    return h.hexdigest()


def load_update_config(out_dir):
    """Load update-config.json; return (managed_paths, excluded_paths) tuple."""
    config_path = os.path.join(out_dir, 'update-config.json')
    if not os.path.isfile(config_path):
        print(f"[update-service] WARNING: No update-config.json found at {config_path}")
        print("[update-service] Using default: scan all files (managed_paths=['*'])")
        return ['*'], []
    try:
        with open(config_path, 'r', encoding='utf-8') as f:
            config = json.load(f)
        paths = config.get('managed_paths', [])
        excluded = config.get('excluded_paths', [])
        if not paths:
            print("[update-service] WARNING: managed_paths is empty, no files will be included")
            return [], excluded
        print(f"[update-service] Loaded config: {len(paths)} managed path(s)")
        for p in paths:
            print(f"  - {p}")
        if excluded:
            print(f"[update-service] Loaded config: {len(excluded)} excluded path(s)")
            for p in excluded:
                print(f"  - {p} (excluded)")
        return paths, excluded
    except (json.JSONDecodeError, OSError) as e:
        print(f"[update-service] ERROR: Failed to load update-config.json: {e}", file=sys.stderr)
        sys.exit(1)


def is_excluded(relpath, excluded_paths):
    """Check if a file matches any excluded path.

    excluded_paths entries can be:
      - Directory (ends with /):  e.g. 'configs/whitelist_example/'  excludes all files under it
      - File (no trailing /):     e.g. 'configs/secret.cfg'          exact file exclusion
    """
    if not excluded_paths:
        return False
    for ep in excluded_paths:
        if ep.endswith('/'):
            # Directory match: relpath must start with this prefix
            if relpath == ep[:-1] or relpath.startswith(ep):
                return True
        else:
            # Exact file match
            if relpath == ep:
                return True
    return False


def is_managed(relpath, managed_paths, excluded_paths=None):
    """Check if a file matches any managed path and is not excluded.

    managed_paths entries can be:
      - Directory (ends with /):  e.g. 'mods/'       matches 'mods/xxx.jar'
      - File (no trailing /):     e.g. 'options.txt' exact match
      - Wildcard '*' matches all files

    A file matching excluded_paths is treated as unmanaged.
    """
    if excluded_paths is None:
        excluded_paths = []

    # Check exclusion first
    if is_excluded(relpath, excluded_paths):
        return False

    if '*' in managed_paths:
        return True
    for mp in managed_paths:
        if mp.endswith('/'):
            # Directory match: relpath must start with this prefix
            if relpath == mp[:-1] or relpath.startswith(mp):
                return True
        else:
            # Exact file match
            if relpath == mp:
                return True
    return False


def sanitize_path(path):
    """Sanitize illegal Unicode characters (e.g. lone surrogates), replace with ?."""
    try:
        # Try encoding as UTF-8: lone surrogates cause errors
        path.encode('utf-8')
        return path
    except UnicodeEncodeError:
        # Process character by character, replacing unencodable ones
        result = []
        for ch in path:
            try:
                ch.encode('utf-8')
                result.append(ch)
            except UnicodeEncodeError:
                result.append('?')
        sanitized = ''.join(result)
        print(f"[update-service] WARNING: Sanitized path: {sanitized}", file=sys.stderr)
        return sanitized


def scan_files(files_dir, managed_paths, excluded_paths=None):
    """Scan directory recursively; return only files within managed_paths and not excluded."""
    if excluded_paths is None:
        excluded_paths = []
    if not os.path.isdir(files_dir):
        print(f"[update-service] ERROR: Directory not found: {files_dir}", file=sys.stderr)
        sys.exit(1)

    files = []
    skipped = 0
    excluded_count = 0
    for root, dirnames, filenames in os.walk(files_dir):
        # Skip hidden directories
        dirnames[:] = [d for d in dirnames if not d.startswith('.')]
        for fname in sorted(filenames):
            if fname.startswith('.'):
                continue
            fpath = os.path.join(root, fname)
            relpath = os.path.relpath(fpath, files_dir)
            # Normalize to forward-slash path separators
            relpath = relpath.replace(os.sep, '/')
            # Sanitize illegal Unicode surrogate characters
            relpath = sanitize_path(relpath)

            # Check if within managed scope and not excluded
            if not is_managed(relpath, managed_paths, excluded_paths):
                if is_excluded(relpath, excluded_paths):
                    excluded_count += 1
                else:
                    skipped += 1
                continue

            file_hash = compute_sha256(fpath)
            size = os.path.getsize(fpath)
            files.append({
                'path': relpath,
                'hash': file_hash,
                'size': size,
            })

    if skipped > 0:
        print(f"[update-service] Skipped {skipped} file(s) not in managed_paths")
    if excluded_count > 0:
        print(f"[update-service] Excluded {excluded_count} file(s) by excluded_paths")
    return files


def main():
    parser = argparse.ArgumentParser(
        description='Minecraft Auto-Update Service - Manifest Generator'
    )
    parser.add_argument(
        '--dir', default=os.environ.get('FILES_DIR', '/data/files'),
        help='Directory to scan (default /data/files)'
    )
    parser.add_argument(
        '--out', default=os.environ.get('DATA_DIR', '/data'),
        help='Output directory for manifest.json (default /data)'
    )
    parser.add_argument(
        '--agent-jar', default=None,
        help='Path to UpdateAgent_core.jar (optional, records self-update info in manifest)'
    )
    args = parser.parse_args()

    files_dir = args.dir
    out_dir = args.out

    # Load update config to determine managed/excluded scope
    managed_paths, excluded_paths = load_update_config(out_dir)

    print(f"[update-service] Scanning: {files_dir}")
    files = scan_files(files_dir, managed_paths, excluded_paths)
    print(f"[update-service] Found {len(files)} managed file(s)")

    manifest = {
        'managed_paths': managed_paths,
        'excluded_paths': excluded_paths,
        'files': files,
    }

    # If --agent-jar specified, attach agent self-update info
    if args.agent_jar and os.path.isfile(args.agent_jar):
        agent_hash = compute_sha256(args.agent_jar)
        agent_size = os.path.getsize(args.agent_jar)
        manifest['agent'] = {
            'path': 'UpdateAgent.jar',
            'hash': agent_hash,
            'size': agent_size,
        }
        print(f"[update-service] Agent info added (hash={agent_hash}, size={agent_size})")
    elif args.agent_jar:
        print(f"[update-service] WARNING: --agent-jar specified but file not found: {args.agent_jar}")

    # Write manifest.json
    manifest_path = os.path.join(out_dir, 'manifest.json')
    with open(manifest_path, 'w', encoding='utf-8') as mf:
        json.dump(manifest, mf, indent=2, ensure_ascii=True)
    print(f"[update-service] Manifest written: {manifest_path}")

    print(f"[update-service] Done! files={len(files)}")


if __name__ == '__main__':
    main()
