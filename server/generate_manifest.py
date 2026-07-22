#!/usr/bin/env python3
"""
Minecraft 自动更新服务 - 资源清单生成工具
由人工在更新资源后手动运行，扫描文件目录并生成 manifest.json 和 version.txt

用法:
    python generate_manifest.py [版本号] [--dir <资源目录>] [--out <输出目录>]

    若不指定版本号，则自动使用当前 Unix 时间戳。
    资源目录默认 /data/files，输出目录默认 /data。
"""

import sys
import os
import json
import hashlib
import time
import argparse


def compute_sha256(filepath):
    """计算文件的 SHA256 哈希值"""
    h = hashlib.sha256()
    with open(filepath, 'rb') as f:
        # 分块读取，避免大文件占用过多内存
        for chunk in iter(lambda: f.read(8192), b''):
            h.update(chunk)
    return h.hexdigest()


def load_update_config(out_dir):
    """加载 update-config.json，返回 managed_paths 列表"""
    config_path = os.path.join(out_dir, 'update-config.json')
    if not os.path.isfile(config_path):
        print(f"[update-service] WARNING: No update-config.json found at {config_path}")
        print("[update-service] Using default: scan all files (managed_paths=['*'])")
        return ['*']
    try:
        with open(config_path, 'r', encoding='utf-8') as f:
            config = json.load(f)
        paths = config.get('managed_paths', [])
        if not paths:
            print("[update-service] WARNING: managed_paths is empty, no files will be included")
            return []
        print(f"[update-service] Loaded config: {len(paths)} managed path(s)")
        for p in paths:
            print(f"  - {p}")
        return paths
    except (json.JSONDecodeError, OSError) as e:
        print(f"[update-service] ERROR: Failed to load update-config.json: {e}", file=sys.stderr)
        sys.exit(1)


def is_managed(relpath, managed_paths):
    """判断文件是否匹配任一管理路径。

    managed_paths 中的条目可以是:
      - 目录(以 / 结尾):  如 'mods/'    匹配 'mods/xxx.jar'
      - 文件(不以 / 结尾): 如 'options.txt' 精确匹配
      - 通配符 '*' 匹配所有文件
    """
    if '*' in managed_paths:
        return True
    for mp in managed_paths:
        if mp.endswith('/'):
            # 目录匹配：文件路径需以该目录开头
            if relpath == mp[:-1] or relpath.startswith(mp):
                return True
        else:
            # 精确文件匹配
            if relpath == mp:
                return True
    return False


def sanitize_path(path):
    """清理路径中的非法 Unicode 字符（如 lone surrogate），替换为 ?"""
    try:
        # 尝试编码为 UTF-8：lone surrogate 会导致错误
        path.encode('utf-8')
        return path
    except UnicodeEncodeError:
        # 逐个字符处理，替换无法编码的字符
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


def scan_files(files_dir, managed_paths):
    """扫描目录下所有文件，只返回 managed_paths 中规定的文件"""
    if not os.path.isdir(files_dir):
        print(f"[update-service] ERROR: Directory not found: {files_dir}", file=sys.stderr)
        sys.exit(1)

    files = []
    skipped = 0
    for root, dirnames, filenames in os.walk(files_dir):
        # 跳过隐藏目录
        dirnames[:] = [d for d in dirnames if not d.startswith('.')]
        for fname in sorted(filenames):
            if fname.startswith('.'):
                continue
            fpath = os.path.join(root, fname)
            relpath = os.path.relpath(fpath, files_dir)
            # 统一使用正斜杠作为路径分隔符
            relpath = relpath.replace(os.sep, '/')
            # 清理非法 Unicode 代理字符
            relpath = sanitize_path(relpath)

            # 检查是否为管理范围内的文件
            if not is_managed(relpath, managed_paths):
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
    return files


def main():
    parser = argparse.ArgumentParser(
        description='Minecraft 自动更新服务 - 资源清单生成工具'
    )
    parser.add_argument(
        'version', nargs='?', default=None,
        help='版本号（默认使用当前 Unix 时间戳）'
    )
    parser.add_argument(
        '--dir', default=os.environ.get('FILES_DIR', '/data/files'),
        help='要扫描的资源目录（默认 /data/files）'
    )
    parser.add_argument(
        '--out', default=os.environ.get('DATA_DIR', '/data'),
        help='manifest.json 和 version.txt 的输出目录（默认 /data）'
    )
    args = parser.parse_args()

    files_dir = args.dir
    out_dir = args.out
    version = args.version or str(int(time.time()))

    # 加载更新配置，确定管理范围
    managed_paths = load_update_config(out_dir)

    print(f"[update-service] Scanning: {files_dir}")
    files = scan_files(files_dir, managed_paths)
    print(f"[update-service] Found {len(files)} managed file(s)")

    manifest = {
        'version': version,
        'managed_paths': managed_paths,
        'files': files,
    }

    # 写入 manifest.json
    manifest_path = os.path.join(out_dir, 'manifest.json')
    with open(manifest_path, 'w', encoding='utf-8') as mf:
        json.dump(manifest, mf, indent=2, ensure_ascii=True)
    print(f"[update-service] Manifest written: {manifest_path}")

    # 写入 version.txt
    version_path = os.path.join(out_dir, 'version.txt')
    with open(version_path, 'w', encoding='utf-8') as vf:
        vf.write(version + '\n')
    print(f"[update-service] Version written: {version_path}")

    print(f"[update-service] Done! version={version}, files={len(files)}")


if __name__ == '__main__':
    main()
