# -*- coding: utf-8 -*-
"""
项目统计器：文件数量、体积、类型分布、源码行数

三个关键点（都是相对旧 PowerShell 版的改动）：
  1) 目录树只走一遍。os.walk 自顶向下，遇到依赖/构建目录当场剪枝（dirnames[:] = ...），
     不会像 Get-ChildItem -Recurse 那样先钻进 node_modules/target 再过滤。
  2) 行数分三档：代码 / 空行 / 注释。只数 CODE_EXTS 里的文本文件，
     并跳过超过 MAX_LINE_COUNT_BYTES 的巨型文件（压缩产物、数据文件）。
  3) 参数从命令行拿（argv），不往代码里拼字面量 —— 路径带空格、带反斜杠都不会出问题。

用法：
    python project_stats.py                       # 统计当前目录
    python project_stats.py --path D:\\projects    # 统计指定目录
"""
import argparse
import os
import sys
from collections import defaultdict

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

# 统计行数时纳入的源码扩展名
CODE_EXTS = {
    '.java', '.kt', '.scala', '.py', '.js', '.ts', '.jsx', '.tsx',
    '.go', '.rs', '.cpp', '.c', '.h', '.hpp', '.cs', '.php', '.rb',
    '.swift', '.m', '.mm', '.xml', '.json', '.yaml', '.yml', '.sql',
    '.sh', '.ps1', '.md', '.html', '.htm', '.css',
}

# 遍历时整棵子树跳过的目录名：依赖、构建产物、缓存、IDE 配置
SKIP_DIRS = {
    'lib', 'target', 'build', 'dist', 'node_modules', 'out', 'bin', 'obj',
    '.git', '.idea', '.vscode', '__pycache__', '.venv', 'venv',
}

# 超过这个体积的文件不做行数统计
MAX_LINE_COUNT_BYTES = 2 * 1024 * 1024

# 行首命中这些前缀就算注释行
COMMENT_PREFIXES = (b'#', b'//', b'/*', b'*', b'--', b'<!--')

# 类型分布表展示的条数
TOP_N = 15


def iter_files(root):
    """自顶向下遍历 root，SKIP_DIRS 里的目录整棵子树都不进。"""
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            yield os.path.join(dirpath, name)


def count_lines(path):
    """返回 (代码行, 空行, 注释行)。

    以二进制方式逐行读，避开不同平台的默认编码差异；非 UTF-8 的老文件也不会中断统计。
    """
    code = blank = comment = 0
    with open(path, 'rb') as f:
        for raw in f:
            line = raw.strip()
            if not line:
                blank += 1
            elif line.startswith(COMMENT_PREFIXES):
                comment += 1
            else:
                code += 1
    return code, blank, comment


def scan(root):
    """走一遍目录树，把所有指标一次算完。"""
    total_files = 0
    total_size = 0
    ext_files = defaultdict(int)                  # 扩展名 -> 文件数
    ext_size = defaultdict(int)                   # 扩展名 -> 字节数
    ext_lines = defaultdict(lambda: [0, 0, 0])    # 扩展名 -> [代码, 空行, 注释]

    for path in iter_files(root):
        try:
            size = os.path.getsize(path)
        except OSError:
            continue
        total_files += 1
        total_size += size

        ext = os.path.splitext(path)[1].lower() or '(none)'
        ext_files[ext] += 1
        ext_size[ext] += size

        if ext in CODE_EXTS and size <= MAX_LINE_COUNT_BYTES:
            try:
                code, blank, comment = count_lines(path)
            except OSError:
                continue
            bucket = ext_lines[ext]
            bucket[0] += code
            bucket[1] += blank
            bucket[2] += comment

    return total_files, total_size, ext_files, ext_size, ext_lines


def human_size(n):
    if n < 1024:
        return '%d B' % n
    if n < 1024 * 1024:
        return '%.1f KB' % (n / 1024.0)
    return '%.1f MB' % (n / 1024.0 / 1024.0)


def render_table(headers, rows, right_cols=()):
    """按列宽对齐的纯文本表格，right_cols 里的列右对齐（数字列）。"""
    widths = [len(h) for h in headers]
    for row in rows:
        for i, cell in enumerate(row):
            widths[i] = max(widths[i], len(cell))

    def line(cells):
        return '  '.join(
            c.rjust(widths[i]) if i in right_cols else c.ljust(widths[i])
            for i, c in enumerate(cells))

    out = [line(headers), line(['-' * w for w in widths])]
    out.extend(line(row) for row in rows)
    return '\n'.join(out)


def main():
    ap = argparse.ArgumentParser(description='统计项目的文件数量、体积、类型分布与源码行数')
    ap.add_argument('--path', help='要扫描的项目根目录，默认为当前目录')
    args = ap.parse_args()

    root = os.path.abspath(args.path) if args.path else os.getcwd()
    if not os.path.isdir(root):
        print('目录不存在: %s' % root)
        return 1

    total_files, total_size, ext_files, ext_size, ext_lines = scan(root)

    src_files = sum(n for e, n in ext_files.items() if e in CODE_EXTS)
    code = sum(v[0] for v in ext_lines.values())
    blank = sum(v[1] for v in ext_lines.values())
    comment = sum(v[2] for v in ext_lines.values())

    print('Project: %s' % root)
    print('Skipped: %s' % ', '.join(sorted(SKIP_DIRS)))
    print()
    print('=== Overview ===')
    print('Total files  : %d' % total_files)
    print('Total size   : %s' % human_size(total_size))
    print('Source files : %d' % src_files)
    print('Source lines : %d  (code %d / blank %d / comment %d)'
          % (code + blank + comment, code, blank, comment))

    print()
    print('=== File Types (Top %d) ===' % TOP_N)
    top = sorted(ext_files.items(), key=lambda kv: (-kv[1], kv[0]))[:TOP_N]
    print(render_table(
        ['Extension', 'Files', 'Size'],
        [[e, str(n), human_size(ext_size[e])] for e, n in top],
        right_cols=(1, 2)))

    print()
    print('=== Source Lines of Code ===')
    rows = sorted(
        ([e, str(ext_files[e]), str(v[0]), str(v[1]), str(v[2])]
         for e, v in ext_lines.items()),
        key=lambda r: (-int(r[2]), r[0]))
    print(render_table(
        ['Extension', 'Files', 'Code', 'Blank', 'Comment'],
        rows, right_cols=(1, 2, 3, 4)))

    print()
    print('Done.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
