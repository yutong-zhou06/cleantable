# -*- coding: utf-8 -*-
"""
从用户提供的真实教务页面里，抽出两张课表并生成单测夹具。

为什么不自己手搓一份理想 HTML 当夹具：那样测的是「我理解的格式」，
真实页面里那些不规整之处（跨行 td、单元格里叠两门课、`教师 ：` 中间带空格）
恰恰是解析器最容易错的地方，只有真实标记才能覆盖到。
"""
import os
import re
import sys

SRC = r"C:\Users\yutongzhou\Downloads\个人课表查询.html"
OUT = r"E:\study\class\cleantable\app\src\test\resources\html"

os.makedirs(OUT, exist_ok=True)
html = open(SRC, encoding="utf-8", errors="replace").read()

HEAD_SEM = "<!DOCTYPE html>\n<html><head><meta charset=\"UTF-8\"><title>个人课表查询</title></head><body>\n"
TAIL = "\n</body></html>\n"


def extract_table(table_id):
    """截取 <table id="..."> 到它自己的 </table>（内部无嵌套表格，取第一个结束标签即可）"""
    m = re.search(r'<table[^>]*id="%s"[^>]*>' % re.escape(table_id), html)
    if not m:
        raise SystemExit("找不到表格：" + table_id)
    start = m.start()
    end = html.index("</table>", m.end()) + len("</table>")
    return html[start:end]


def write(name, content, encoding="utf-8"):
    p = os.path.join(OUT, name)
    with open(p, "w", encoding=encoding, newline="\n") as f:
        f.write(content)
    print(f"  {name}  ({len(content)} 字符, {encoding})")


# 1) 列表模式（正方新版主推的解析路径）
write("zhengfang_list.html", HEAD_SEM + extract_table("kblist_table") + TAIL)

# 2) 课表模式（兜底路径）
write("zhengfang_grid.html", HEAD_SEM + extract_table("kbgrid_table_0") + TAIL)

# 3) GBK 编码的小样本 —— 专测编码探测。
#    国内教务系统大量是 GB2312/GBK，按 UTF-8 硬读会整片乱码，
#    而乱码是「能读出内容但内容全错」，最难被发现，必须单独测。
gbk_courses = [
    (1, "高等数学（上）", "1-2", "1-16", "A栋201", "张三"),
    (3, "大学物理实验", "3-4", "1-8", "物理楼B305", "李四"),
]
bodies = []
for d, name, node, weeks, room, teacher in gbk_courses:
    start, end = node.split("-")
    bodies.append(
        '<tbody id="xq_%d"><tr><td id="jc_%d-%s-%s" rowspan="1">'
        '<span class="festival">%s</span></td>'
        '<td><div class="timetable_con text-left"><span class="title">'
        '<font color="blue">%s</font></span><p><font color="blue">'
        '<span class="glyphicon glyphicon-calendar"></span> 周数：%s周</font>'
        '<font color="blue"><span class="glyphicon glyphicon-tower"></span> '
        '校区:某某大学<span class="glyphicon glyphicon-map-marker"></span> 上课地点：%s </font>'
        '<font color="blue"><span class="glyphicon glyphicon-user"></span> 教师 ：%s</font>'
        "</p></div></td></tr></tbody>" % (d, d, start, end, node, name, weeks, room, teacher)
    )

gbk_html = (
    "<!DOCTYPE html>\n<html><head><meta http-equiv=\"Content-Type\" "
    "content=\"text/html; charset=gb2312\"><title>个人课表查询</title></head><body>\n"
    '<div class="tab-pane fade" id="table2"><table id="kblist_table" class="table">'
    '<tbody><tr><td colspan="4"><div class="timetable_title">'
    '<h6 class="pull-left">2026-2027学年第2学期</h6>某某的课表</div></td></tr></tbody>'
    + "".join(bodies) +
    '<tbody><tr><td colspan="4" style="text-align:left;">'
    '<div class="timetable_title"><span class="red">实践课程：</span>'
    "<span>金工实习王五(共1周)/17周;</span><br></div></td></tr></tbody>"
    "</table></div>\n</body></html>\n"
)

p = os.path.join(OUT, "zhengfang_gbk.html")
with open(p, "wb") as f:
    f.write(gbk_html.encode("gb18030"))
print(f"  zhengfang_gbk.html  ({len(gbk_html.encode('gb18030'))} 字节, gb2312/gb18030)")

# 4) MHTML（浏览器「网页，单个文件」）—— 专测 MhtmlDecoder 拆信封。
#    不重新编码、不手工拼接：保留真实根头部 + 真实 boundary + 一个真实 CSS 部件
#    + 完整的主 HTML 部件（quoted-printable 编码原样保留）。
#    砍掉其余 CSS/PNG 部件只是为了仓库体积，结构上的「多部件 + 挑 HTML」考验不变。
MHTML_SRC = r"C:\Users\yutongzhou\Downloads\个人课表查询.mhtml"
raw = open(MHTML_SRC, "rb").read()
m = re.search(rb'boundary="?(.+?)"?\s*\r?\n', raw[:900])
boundary = m.group(1)
# split 后每段以 \r\n 开头（除了 prologue），直接用原字节回拼，保证 QP 数据不被触碰
pieces = raw.split(b"--" + boundary)
html_piece = next(p for p in pieces if b"Content-Type: text/html" in p[:600])
css_piece = min(
    (p for p in pieces if b"Content-Type: text/css" in p[:600]),
    key=len,
)
closing = pieces[-1]  # 以 "--\r\n"（closing boundary 残留）开头
mhtml_fixture = (
    pieces[0]                      # 根头部 + 序言
    + b"--" + boundary
    + css_piece                    # 一个真实 CSS 部件（考验「挑对部件」）
    + b"--" + boundary
    + html_piece                   # 主 HTML 部件（quoted-printable）
    + b"--" + boundary
    + closing
)
p = os.path.join(OUT, "zhengfang_mhtml.mhtml")
with open(p, "wb") as f:
    f.write(mhtml_fixture)
print(f"  zhengfang_mhtml.mhtml  ({len(mhtml_fixture)} 字节, multipart/related)")

print("\n夹具生成完毕：", OUT)
