"""
知海检索详情页爬虫

输入：spider/data/books_raw.json（列表页已抓取的图书）
输出：spider/data/books_final.json（补充 ISBN / 完整作者 / 完整简介）

详情页字段（参考 https://www.zhihailib.com/book/260968）：
  作者 / 译者 / 语言 / 类型 / 页数 / 大小 / 出版社 / 出版时间 / ISBN / 分类 / 内容简介 / 作者简介 / 目录 / 标签

本脚本只补：
  - ISBN（必需，books 表唯一约束）
  - publisher（出版社，列表摘要中已有但详情更可靠）
  - publish_date（出版时间）
  - author（作者）
  - description（完整内容简介）
  - pages（页数，可选）
"""
import json
import random
import re
import time
from pathlib import Path

import requests
from bs4 import BeautifulSoup

from config import (
    BOOKS_FINAL_FILE,
    BOOKS_RAW_FILE,
    COVER_URL_PREFIX,
    DEFAULT_PRICE,
    LOCATION_TEMPLATES,
    MAX_DESC_LENGTH,
    MAX_RETRIES,
    MIN_PRICE,
    REQUEST_DELAY_MAX,
    REQUEST_DELAY_MIN,
    REQUEST_TIMEOUT,
    RETRY_DELAY,
    TOTAL_COUNT_MAX,
    TOTAL_COUNT_MIN,
    USER_AGENTS,
    ZHIHAI_DETAIL_URL_PATTERN,
)


def get_ua() -> str:
    return random.choice(USER_AGENTS)


def sleep_jitter(min_s: float, max_s: float) -> None:
    time.sleep(random.uniform(min_s, max_s))


def fetch_page(url: str) -> str:
    last_err = None
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            r = requests.get(
                url,
                headers={
                    "User-Agent": get_ua(),
                    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
                    "Connection": "close",
                },
                timeout=REQUEST_TIMEOUT,
            )
            if r.status_code == 200 and len(r.text) > 1000:
                return r.text
            print(f"  [warn] {url} 状态={r.status_code} 第{attempt}次")
        except requests.RequestException as e:
            last_err = e
            print(f"  [warn] {url} 异常={e} 第{attempt}次")
        sleep_jitter(RETRY_DELAY, RETRY_DELAY * 1.5)
    raise RuntimeError(f"详情页抓取失败 {url}: {last_err}")


# 详情页字段提取器（基于 <div class="meta-label">/<div class="meta-value"> 结构）
def extract_field(soup: BeautifulSoup, label_keywords: list) -> str:
    """从详情页 meta-label/meta-value 行里按关键词找值"""
    for label_div in soup.find_all("div", class_="meta-label"):
        label_text = label_div.get_text(strip=True).rstrip("：:")
        if any(kw in label_text for kw in label_keywords):
            # 同一 meta-row 下的 meta-value
            row = label_div.parent
            if row:
                value_div = row.find("div", class_="meta-value")
                if value_div:
                    return value_div.get_text(strip=True)
    return ""


def parse_detail_page(html: str) -> dict:
    """解析详情页，返回补全字段"""
    soup = BeautifulSoup(html, "lxml")

    # ISBN：先找 dt/dd，再 fallback 正则
    isbn = extract_field(soup, ["ISBN"])
    if not isbn:
        m = re.search(r"ISBN[：:]\s*([0-9Xx]{10,17})", html)
        if m:
            isbn = m.group(1).strip()

    author = extract_field(soup, ["作者"])
    publisher = extract_field(soup, ["出版社"])
    publish_date = extract_field(soup, ["出版时间", "出版日期"])
    pages_str = extract_field(soup, ["页数"])
    try:
        pages = int(re.search(r"\d+", pages_str).group()) if pages_str and re.search(r"\d+", pages_str) else 0
    except Exception:
        pages = 0

    # 内容简介：找 h2#/h2# 后的段落
    desc = ""
    for h in soup.find_all(["h2", "h3"]):
        if "内容简介" in h.get_text(strip=True):
            # 取该标题后到下一个同级标题之间的所有 <p>
            parts = []
            for sib in h.find_next_siblings():
                if sib.name in ("h2", "h3"):
                    break
                if sib.name == "p":
                    parts.append(sib.get_text(strip=True))
            desc = "\n".join(p for p in parts if p)
            if desc:
                break

    # fallback：列表 excerpt 已含简介，详情页抓不到时由调用方兜底
    return {
        "isbn": isbn,
        "author": author,
        "publisher": publisher,
        "publish_date": publish_date,
        "pages": pages,
        "description": desc,
    }


def gen_isbn_fallback(book_id: str) -> str:
    """详情页拿不到 ISBN 时，用知海 ID 生成一个合成的 13 位 ISBN（以 9 开头表示非标准分配）"""
    # 真实 ISBN13 是 978/979 开头，这里用 9 + 12 位数字避免与真实冲突
    padded = str(book_id).zfill(12)
    return "9" + padded[-12:]


def gen_price() -> float:
    """生成符合后端约束的随机价格（>=10 元）"""
    price = round(random.uniform(MIN_PRICE, MIN_PRICE * 10), 2)  # 10 ~ 100
    return price


def gen_location() -> str:
    """生成随机馆藏位置"""
    import random as _r
    tpl = _r.choice(LOCATION_TEMPLATES)
    return tpl.format(row=_r.randint(1, 20), shelf=_r.randint(1, 12))


def gen_total_count() -> int:
    """生成随机馆藏数量"""
    return random.randint(TOTAL_COUNT_MIN, TOTAL_COUNT_MAX)


def enrich_one(book: dict) -> dict:
    """对一条列表记录补全详情"""
    url = ZHIHAI_DETAIL_URL_PATTERN.format(book_id=book["book_id"])
    try:
        html = fetch_page(url)
        detail = parse_detail_page(html)
    except RuntimeError as e:
        print(f"  详情抓取失败 book_id={book['book_id']}: {e}，用兜底")
        detail = {"isbn": "", "author": "", "publisher": "", "publish_date": "", "pages": 0, "description": ""}

    # ISBN 兜底
    isbn = detail["isbn"] or gen_isbn_fallback(book["book_id"])

    # 作者兜底：从 excerpt 提取
    author = detail["author"]
    if not author and book.get("excerpt"):
        parts = book["excerpt"].split(" / ")
        if len(parts) >= 2 and parts[1]:
            author = parts[1].strip()

    # 出版社兜底
    publisher = detail["publisher"]
    if not publisher and book.get("excerpt"):
        parts = book["excerpt"].split(" / ")
        for p in parts:
            if any(k in p for k in ["出版社", "出版公司"]):
                publisher = p.strip()
                break

    # 出版日期兜底
    publish_date = detail["publish_date"]
    if not publish_date and book.get("excerpt"):
        parts = book["excerpt"].split(" / ")
        for p in parts:
            m = re.match(r"\d{4}[-/]\d{1,2}([-/]\d{1,2})?", p.strip())
            if m:
                publish_date = m.group(0).replace("/", "-")
                break

    # 简介兜底
    desc = detail["description"]
    if not desc and book.get("excerpt"):
        parts = book["excerpt"].split(" / ", 4)
        if len(parts) >= 5:
            desc = parts[4].strip()
    if len(desc) > MAX_DESC_LENGTH:
        desc = desc[:MAX_DESC_LENGTH].rstrip() + "…"

    # 封面 URL：用列表页直链（已知）
    cover_url = book.get("cover_url", "")
    local_cover_url = f"{COVER_URL_PREFIX}{book['book_id']}.jpg" if cover_url else ""

    return {
        "book_id": book["book_id"],
        "title": book["title"],
        "author": author or "佚名",
        "publisher": publisher,
        "isbn": isbn,
        "publish_date": publish_date,
        "description": desc,
        "pages": detail["pages"],
        "remote_cover_url": cover_url,
        "cover_url": local_cover_url,
        "category_id": book.get("third_category_id", 0),
        "top_code": book.get("top_code", ""),
        "price": gen_price(),
        "location": gen_location(),
        "total_count": gen_total_count(),
        "zhihai_class": book.get("zhihai_class", ""),
        "source_list": book.get("source_list", ""),
    }


def main() -> None:
    if not BOOKS_RAW_FILE.exists():
        print(f"[error] 找不到 {BOOKS_RAW_FILE}，先运行 crawl_list.py")
        return

    books = json.loads(BOOKS_RAW_FILE.read_text(encoding="utf-8"))
    print(f"=== 详情页补全启动，共 {len(books)} 本 ===")

    enriched = []
    for i, b in enumerate(books, 1):
        print(f"[{i}/{len(books)}] book_id={b['book_id']} {b['title'][:24]}")
        rec = enrich_one(b)
        enriched.append(rec)
        sleep_jitter(REQUEST_DELAY_MIN, REQUEST_DELAY_MAX)

    BOOKS_FINAL_FILE.write_text(
        json.dumps(enriched, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"\n=== 详情补全完成，写入 {BOOKS_FINAL_FILE} ===")
    # 统计 ISBN 命中率：ISBN 不等于合成格式（gen_isbn_fallback 输出）即视为真实
    real_isbn = sum(
        1 for r in enriched
        if r["isbn"] != gen_isbn_fallback(r["book_id"])
    )
    synthetic = len(enriched) - real_isbn
    print(f"ISBN 来源：详情页真实 {real_isbn} 本 / 合成兜底 {synthetic} 本")


if __name__ == "__main__":
    main()
