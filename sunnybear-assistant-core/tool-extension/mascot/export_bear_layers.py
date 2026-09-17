# -*- coding: utf-8 -*-
"""
看板熊分层素材导出器（PSD → live/*.png + bear-geometry.js 几何记录）

为什么会踩坑，先看这三条：
  1) PSD 画布坐标 ≠ 项目里的"内容区"坐标。内容区是画布里裁出来的一块，
     当前偏移是 y 方向 -196（PSD 的 (343,398) 就是内容区的 (343,202)）。
     本脚本会自动推导这个偏移：拿 PSD 各层的 bbox 与现有 bear-geometry.js 里
     同名层的 px 对照，取众数，不写死。
  2) `face` 图层里有极低 alpha 的杂点会铺满整个画布，按 alpha>0 裁剪会得到
     1024×1024 的巨图。所以 face 这类层要用 --trim-thr 32 裁剪（默认 1）。
  3) psd_tools 的 layer.numpy() 返回的数组是**相对图层 bbox** 的，不是整张画布，
     算绝对坐标时要再加一次 layer.bbox 原点（这个坑本脚本踩过）。
  4) 眼睛（眼白+眼仁）必须压在 face 底下，靠 face 上透明的眼窝露出来；
     睫毛要留在 face 之上，否则会被脸皮整条吃掉。层序见 mascot-live.js。

用法：
    python tool-extension/mascot/export_bear_layers.py --dry-run              # 只看结果，不写文件
    python tool-extension/mascot/export_bear_layers.py                        # 导出全部层
    python tool-extension/mascot/export_bear_layers.py --layers face face-r-eye --trim-thr 32
    python tool-extension/mascot/export_bear_layers.py --psd "D:\\x.psd"
"""
import argparse
import collections
import glob
import os
import re
import sys

import numpy as np
from PIL import Image
from psd_tools import PSDImage

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))


def find_core(start):
    """向上找含 pom.xml 且带 src 的目录 —— 脚本放哪个子目录都能定位到项目根"""
    d = start
    while True:
        if os.path.isfile(os.path.join(d, 'pom.xml')) and os.path.isdir(os.path.join(d, 'src')):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            raise SystemExit('找不到项目根（含 pom.xml 和 src 的目录）')
        d = parent


CORE = find_core(HERE)
DEFAULT_OUT = os.path.join(CORE, 'src', 'main', 'resources', 'static',
                           'icon', 'signboard_bear', 'live')
GEO_NAME = 'bear-geometry.js'
DEFAULT_CONTENT = (1008, 768)


def find_psd():
    desktop = os.path.join(os.path.expanduser('~'), 'Desktop')
    cands = sorted(glob.glob(os.path.join(desktop, '*.psd')),
                   key=os.path.getmtime, reverse=True)
    if not cands:
        raise SystemExit('桌面上没找到 .psd，请用 --psd 指定路径')
    return cands[0]


def parse_geometry(path):
    if not os.path.exists(path):
        return {}, DEFAULT_CONTENT
    src = open(path, encoding='utf-8').read()
    pat = re.compile(r'name: "([^"]+)".*?px: \[(-?\d+), (-?\d+), (-?\d+), (-?\d+)\]')
    out = {}
    for m in pat.finditer(src):
        out[m.group(1)] = [int(m.group(i)) for i in range(2, 6)]
    cs = re.search(r'contentSize: \[(\d+), (\d+)\]', src)
    content = (int(cs.group(1)), int(cs.group(2))) if cs else DEFAULT_CONTENT
    return out, content


def flatten(psd):
    flat = {}

    def walk(layer):
        for l in layer:
            flat[l.name] = l
            if l.is_group():
                walk(l)

    walk(psd)
    return flat


def to_rgba(arr):
    if arr.dtype != np.uint8:
        a = arr.astype(np.float32)
        if a.max() <= 1.0:
            a *= 255.0
        arr = np.clip(a, 0, 255).astype(np.uint8)
    return arr


def trim_bbox(arr, thr):
    """返回相对图层 bbox 的裁剪框 (x0, y0, x1, y1)"""
    a = arr[:, :, 3]
    ys, xs = np.nonzero(a > thr)
    if len(xs) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--psd', help='PSD 路径（默认取桌面最新的那个）')
    ap.add_argument('--out', default=DEFAULT_OUT, help='PNG 输出目录')
    ap.add_argument('--geometry', help='bear-geometry.js 路径（默认在 --out 下）')
    ap.add_argument('--layers', nargs='*', help='只导出这些层（默认全部像素层）')
    ap.add_argument('--trim-thr', type=int, default=1,
                    help='裁剪阈值：alpha > 该值才算内容。face 这类有低 alpha 杂点的层用 32')
    ap.add_argument('--expand', type=int, default=0,
                    help='裁剪框向外扩 N 像素。face 配 --trim-thr 32 会切掉最外圈抗锯齿，加 --expand 1 还原')
    ap.add_argument('--dry-run', action='store_true', help='只打印，不写文件')
    args = ap.parse_args()

    psd_path = args.psd or find_psd()
    geo_path = args.geometry or os.path.join(args.out, GEO_NAME)
    print('PSD      :', psd_path)
    print('输出目录 :', args.out)
    print('几何文件 :', geo_path)

    psd = PSDImage.open(psd_path)
    flat = flatten(psd)
    geo, content = parse_geometry(geo_path)
    print('PSD 尺寸 :', psd.size, ' 内容区 :', content)

    # ---- 推导 PSD 画布坐标 -> 内容区坐标 的偏移 ----
    # 绝对坐标 = layer.bbox 原点 + 相对裁剪偏移
    votes = collections.Counter()
    for name, px in geo.items():
        layer = flat.get(name)
        if layer is None or not layer.is_visible():
            continue
        bb = trim_bbox(to_rgba(np.asarray(layer.numpy())), 1)
        if bb is None:
            continue
        bx, by = int(layer.bbox[0]), int(layer.bbox[1])
        votes[(px[0] - (bx + bb[0]), px[1] - (by + bb[1]))] += 1
    if votes:
        (off_x, off_y), n = votes.most_common(1)[0]
        print('推导偏移 : x %+d, y %+d  (%d 层一致)' % (off_x, off_y, n))
        if len(votes) > 1:
            print('          注：其余候选 =', votes.most_common()[1:4])
    else:
        off_x, off_y = 0, 0
        print('推导偏移 : 无参照层，按 0 处理')

    names = args.layers or [n for n, l in flat.items() if not l.is_group()]
    print('\n导出 %d 层（trim-thr=%d）:' % (len(names), args.trim_thr))
    cw, ch = content
    for name in names:
        layer = flat.get(name)
        if layer is None:
            print('  [MISS] %s' % name)
            continue
        arr = to_rgba(np.asarray(layer.numpy()))
        bb = trim_bbox(arr, args.trim_thr)
        if bb is None:
            print('  [EMPTY] %s' % name)
            continue
        x0, y0, x1, y1 = bb
        if args.expand:
            x0 = max(0, x0 - args.expand)
            y0 = max(0, y0 - args.expand)
            x1 = min(arr.shape[1], x1 + args.expand)
            y1 = min(arr.shape[0], y1 + args.expand)
        bx, by = int(layer.bbox[0]), int(layer.bbox[1])
        crop = arr[y0:y1, x0:x1]
        w, h = x1 - x0, y1 - y0
        px = [bx + x0 + off_x, by + y0 + off_y, w, h]
        filename = name.replace('-', '_') + '.png'

        line = ('  { name: "%s", file: "live/%s", group: "head", '
                'x: %.4f, y: %.4f, w: %.4f, h: %.4f, px: [%d, %d, %d, %d] },'
                % (name, filename, px[0] / cw * 100, px[1] / ch * 100,
                   w / cw * 100, h / ch * 100, px[0], px[1], w, h))
        print('  %-12s -> %-18s %dx%d%s' % (name, filename, w, h,
                                            '  (dry-run)' if args.dry_run else ''))
        print(line)
        if not args.dry_run:
            os.makedirs(args.out, exist_ok=True)
            Image.fromarray(crop).save(os.path.join(args.out, filename))

    if args.dry_run:
        print('\n[dry-run] 没有写任何文件')


if __name__ == '__main__':
    main()
