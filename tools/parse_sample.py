# -*- coding: utf-8 -*-
"""
正方教务（jwglxt 新版）课表页解析原型。

目的：在写 Kotlin 解析器之前，先把规则跑通、把结果打印出来人工核对。
解析目标：<table id="kblist_table">（列表模式）——比课表模式规整得多。

用法：python parse_sample.py <html路径>
"""
import re
import sys
import json
from html.parser import HTMLParser

CN_NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "日": 7, "天": 7}
NAME_MARK = "\u241e"   # span.title 起始标记（课程名）
NAME_END = "\u241f"    # span.title 结束标记


class TableReader(HTMLParser):
    """把 <table id=...> 内部拍平成 行 -> 单元格 的二维结构，保留 td 的 id。"""

    def __init__(self, table_id):
        super().__init__(convert_charrefs=True)
        self.table_id = table_id
        self.in_table = 0
        self.in_title = 0
        self.rows = []
        self.cur_row = None
        self.cur_cell = None
        self.cell_depth = 0

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "table":
            if a.get("id") == self.table_id:
                self.in_table = 1
            elif self.in_table:
                self.in_table += 1
            return
        if not self.in_table:
            return
        if tag == "tr":
            self.cur_row = []
        elif tag == "td":
            self.cell_depth += 1
            self.cur_cell = {"id": a.get("id", ""), "text": ""}
            if self.cur_row is not None:
                self.cur_row.append(self.cur_cell)
        elif tag == "span" and a.get("class", "").strip() == "title":
            self.in_title += 1

    def handle_endtag(self, tag):
        if tag == "table" and self.in_table:
            self.in_table -= 1
            if self.in_table == 0:
                self.rows.append(None)
            return
        if not self.in_table:
            return
        if tag == "tr":
            if self.cur_row:
                self.rows.append(self.cur_row)
            self.cur_row = None
        elif tag == "td":
            self.cell_depth = max(0, self.cell_depth - 1)
            if self.cell_depth == 0:
                self.cur_cell = None
        elif tag == "span" and self.in_title:
            self.in_title -= 1
            if self.cur_cell is not None:
                self.cur_cell["text"] += NAME_END

    def handle_data(self, data):
        if not self.in_table or self.cur_cell is None or not data.strip():
            return
        if self.in_title:
            self.cur_cell["text"] += NAME_MARK + data.strip()
        else:
            self.cur_cell["text"] += data.strip() + " "


def clean(s):
    return re.sub(r"[ \t\u00a0]+", " ", s or "").strip()


def parse_weeks(body):
    """解析 '周数：1-12周' / '1-12周(单)' / '1,3,5-7周'。返回 (weeks, parity, raw)。"""
    m = re.search(r"周数[：:]\s*([0-9,\-\u2013\u2014]+)\s*周\s*(?:[（(]\s*(单|双)\s*[)）])?", body)
    if not m:
        m = re.search(r"(?:^|[\s(])([0-9][0-9,\-\u2013\u2014]*)\s*周\s*(?:[（(]\s*(单|双)\s*[)）])?", body)
    if not m:
        return None, None, ""
    parity = {"单": "ODD", "双": "EVEN"}.get(m.group(2) or "")
    weeks = set()
    for part in re.split(r"[,，]", m.group(1)):
        part = re.sub(r"[\u2013\u2014]", "-", part.strip())
        if not part:
            continue
        if "-" in part:
            a, _, b = part.partition("-")
            if a.isdigit() and b.isdigit() and int(a) <= int(b) and int(b) - int(a) < 60:
                weeks.update(range(int(a), int(b) + 1))
        elif part.isdigit():
            weeks.add(int(part))
    weeks = sorted(w for w in weeks if 1 <= w <= 60)
    return (weeks or None), parity, m.group(0)


def parse_weekday(name):
    m = re.search(r"星期([一二三四五六日天])", name)
    return CN_NUM.get(m.group(1)) if m else None


def parse_nodes(txt):
    """'1-4' -> (1,4)；'3' -> (3,3)；否则 None"""
    t = clean(txt)
    m = re.fullmatch(r"(\d{1,2})(?:\s*[-–—]\s*(\d{1,2}))?", t)
    if not m:
        return None
    a = int(m.group(1))
    b = int(m.group(2)) if m.group(2) else a
    return (a, b) if a <= b else None


def main(path):
    html = open(path, encoding="utf-8", errors="replace").read()
    p = TableReader("kblist_table")
    p.feed(html)

    courses = []
    cur_day = None
    cur_node = None

    for row in p.rows:
        if row is None:
            break
        cells = list(row)
        if not cells:
            continue

        d = parse_weekday(cells[0]["text"])
        if d:
            cur_day = d
            cur_node = None
            cells = cells[1:]
        if cur_day is None:
            continue

        for cell in cells:
            t = cell["text"]
            mid = re.match(r"jc_(\d+)-(\d+)-(\d+)", cell["id"])
            if mid:
                cur_node = (int(mid.group(2)), int(mid.group(3)))
            elif NAME_MARK not in t:
                nd = parse_nodes(t)
                if nd:
                    cur_node = nd
            if NAME_MARK not in t:
                continue

            # 单元格文本：  NAME课名NAME_END 详情  NAME课名NAME_END 详情 …
            for seg in t.split(NAME_MARK)[1:]:
                name, sep, body = seg.partition(NAME_END)
                name = clean(name)
                if not name or not sep:
                    continue
                weeks, parity, raw = parse_weeks(body)
                lm = re.search(r"上课地点[：:]\s*(.*?)(?=\s*教师\s*[：:]|$)", body)
                tm = re.search(r"教师\s*[：:]\s*([^\u241e\u241f]*)", body)
                room = clean(lm.group(1)) if lm else ""
                if room in ("未排地点", "未安排", "无"):
                    room = ""
                courses.append({
                    "day": cur_day,
                    "nodeStart": cur_node[0] if cur_node else None,
                    "nodeEnd": cur_node[1] if cur_node else None,
                    "name": name,
                    "weeks": weeks,
                    "parity": parity,
                    "rawWeek": raw,
                    "room": room,
                    "teacher": clean(tm.group(1)) if tm else "",
                })

    print(f"解析到课程条目：{len(courses)}\n")
    print(f"{'星期':<5}{'节次':<7}{'课程名':<26}{'周次':<10}{'教室':<34}教师")
    print("-" * 110)
    for c in courses:
        wk = c["rawWeek"].replace("周数：", "").replace("周", "")
        rng = f"{c['nodeStart']}-{c['nodeEnd']}"
        print(f"{c['day']:<7}{rng:<9}{c['name']:<28}{wk:<12}{c['room']:<36}{c['teacher']}")

    return courses


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else
         r"C:\Users\yutongzhou\Downloads\个人课表查询.html")
