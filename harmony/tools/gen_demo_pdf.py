#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate a text-bearing demo PDF for Librera (HarmonyOS port).

5 pages, Chinese + English content, dual fonts:
  F1 = MuPDF built-in CJK font (zh-Hans), used for CJK runs (hex UTF-16BE)
  F2 = Helvetica (base-14), used for Latin runs
Output: test.pdf (replaces the old image-only placeholder demo).
"""
import subprocess

PAGE_W, PAGE_H = 612, 792
TOP = 700
LINE = 42
FOOT_Y = 60

def hex16(s):
    return ''.join('%04X' % ord(c) for c in s)

def is_cjk(o):
    return (0x2E80 <= o <= 0x9FFF) or (0x3000 <= o <= 0x303F) or \
           (0xFF00 <= o <= 0xFFEF) or o in (0x2014, 0x2018, 0x2019,
                                            0x201C, 0x201D, 0x2026)

def runs_of(text):
    runs = []
    cur = None
    for ch in text:
        cjk = is_cjk(ord(ch))
        if cur is None or cur[0] != cjk:
            cur = [cjk, '']
            runs.append(cur)
        cur[1] += ch
    return runs

def line(out, x, y, size, text):
    out.append('BT')
    out.append('1 0 0 1 %d %d Tm' % (x, y))
    for cjk, s in runs_of(text):
        if cjk:
            out.append('/F1 %d Tf' % size)
            out.append('<%s> Tj' % hex16(s))
        else:
            out.append('/F2 %d Tf' % size)
            esc = s.replace('\\', '\\\\').replace('(', '\\(').replace(')', '\\)')
            out.append('(%s) Tj' % esc)
    out.append('ET')

def footer(out, page_no, total):
    line(out, 72, FOOT_Y, 11, 'Librera Reader - HarmonyOS demo  |  %d/%d' % (page_no, total))

PAGES = [
    [
        (36, 34, 'Librera Reader'),
        (24, 16, '鸿蒙版移植演示文档'),
        (14, 10, 'A porting demo of the Android e-book reader Librera to HarmonyOS.'),
        (14, 10, '本文件同时用于验证 TTS 朗读、速读 RSVP、搜索与文本提取。'),
    ],
    [
        (26, 20, '已实现的主要功能'),
        (16, 12, '1. 双滚动模式：水平翻页与垂直滚动，支持双页与乐谱阅读；'),
        (16, 12, '2. 手势缩放、夜间模式、亮度调节与自动翻页；'),
        (16, 12, '3. 全书搜索、书内高亮与手绘批注、书签与阅读进度；'),
        (16, 12, '4. TTS 朗读：按句播放，可调语速与音调，读完自动翻页；'),
        (16, 12, '5. 速读 RSVP：逐词闪现，可调每分钟字数，读完自动翻页；'),
        (16, 12, '6. 目录跳转、文件打开、最近阅读与收藏。'),
    ],
    [
        (24, 18, 'An English paragraph'),
        (16, 12, 'The quick brown fox jumps over the lazy dog. '
                 'Speed reading shows one word at a time, at a pace you control. '
                 'Text to speech reads every sentence aloud. '
                 'Search finds any word on any page.'),
        (16, 12, 'This paragraph exercises the Latin tokenizer and sentence '
                 'splitter used by RSVP and TTS.'),
    ],
    [
        (24, 18, '中文段落测试'),
        (16, 12, '速读是一种高效的阅读训练方法：把文字逐词显示在屏幕中央，眼睛不需要移动，注意力更集中。'),
        (16, 12, '本段落用于验证中文分词与按句断句。中文没有空格，分词器按字切分，句号、问号与感叹号都作为句子边界。'),
        (16, 12, '朗读模式下，引擎会一句一句地读出文本，语速与音调都可以调节。读完当前页会自动进入下一页。'),
    ],
    [
        (22, 16, '结束语'),
        (16, 12, '感谢使用鸿蒙版 Librera Reader。'),
        (16, 12, 'The end. Thank you for reading this demo document.'),
    ],
]

def page_block(items, page_no, total):
    """One mutool-create input file per PDF page."""
    out = []
    out.append('%%%%MediaBox 0 0 %d %d' % (PAGE_W, PAGE_H))
    out.append('%%CJKFont F1 zh-Hans H sans')
    out.append('%%Font F2 Helvetica')
    y = TOP
    for size, lead, text in items:
        line(out, 72, y, size, text)
        y -= size + lead
    footer(out, page_no, total)
    return '\n'.join(out) + '\n'

page_files = []
total = len(PAGES)
for idx, items in enumerate(PAGES):
    pf = '/tmp/demo_page_%d.txt' % (idx + 1)
    with open(pf, 'w', encoding='utf-8') as f:
        f.write(page_block(items, idx + 1, total))
    page_files.append(pf)

MUTOOL = '/docker/opt/librera/LibreraReader/Builder/mupdf-1.23.7/build/release/mutool'
OUT = '/docker/opt/librera/LibreraReader/harmony/entry/src/main/resources/rawfile/test.pdf'
r = subprocess.run([MUTOOL, 'create', '-o', OUT] + page_files,
                   capture_output=True, text=True)
print('mutool stdout:', r.stdout)
print('mutool stderr:', r.stderr)
print('returncode:', r.returncode)
if r.returncode == 0:
    i = subprocess.run([MUTOOL, 'info', OUT], capture_output=True, text=True)
    print('--- info ---')
    print(i.stdout[:400])
    x = subprocess.run([MUTOOL, 'draw', '-F', 'txt', OUT],
                       capture_output=True, text=True)
    print('--- extracted text ---')
    print(x.stdout[:600])
