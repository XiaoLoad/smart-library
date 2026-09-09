"""
知海检索列表页爬虫

输入：ZHIHAI_CATEGORY_SOURCES（每个本地一级分类 → 中图法大类列表）+ CATEGORY_QUOTA
输出：spider/data/books_raw.json（去重后的全部图书原始记录）

每条记录字段：
  book_id        知海图书 ID（唯一）
  title          书名
  cover_url      封面直链（jpg）
  excerpt        列表摘要：类型 / 作者 / 译者 / 出版社 / 出版日期 / 简介
  zhihai_class   中图法大类字母（i/k/b/...）
  source_list    抓取来源中图法大类（便于追溯）
"""
import json
import random
import re
import time
from pathlib import Path

import requests
from bs4 import BeautifulSoup

from config import (
    BOOKS_RAW_FILE,
    CATEGORY_QUOTA,
    DEBUG_MODE,
    MAX_RETRIES,
    REQUEST_DELAY_MAX,
    REQUEST_DELAY_MIN,
    REQUEST_TIMEOUT,
    RETRY_DELAY,
    USER_AGENTS,
    ZHIHAI_CATEGORY_SOURCES,
    ZHIHAI_LIST_URL_PATTERN,
    ZHIHAI_MAX_PAGES_PER_CLASS,
    ZHIHAI_PAGE_SIZE,
)
from category_mapping import ZHONGTU_CLASS_TO_TOP, classify_book


def get_ua() -> str:
    return random.choice(USER_AGENTS)


def sleep_jitter(min_s: float, max_s: float) -> None:
    time.sleep(random.uniform(min_s, max_s))


def fetch_page(url: str) -> str:
    """带重试的页面请求"""
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
            print(f"  [warn] {url} 状态={r.status_code} 长度={len(r.text)} 第{attempt}次")
        except requests.RequestException as e:
            last_err = e
            print(f"  [warn] {url} 异常={e} 第{attempt}次")
        sleep_jitter(RETRY_DELAY, RETRY_DELAY * 1.5)
    raise RuntimeError(f"页面抓取失败 {url}: {last_err}")


# 列表项正则：从 id="item-{book_id}" 提取，稳健
LI_PATTERN = re.compile(
    r'<li\s+class="post-list-item[^"]*"\s+id="item-(\d+)">(.*?)(?=<li\s+class="post-list-item|</ul>)',
    re.S,
)
TITLE_PATTERN = re.compile(r'<h2>\s*<a[^>]*>(.*?)</a>\s*</h2>', re.S)
COVER_PATTERN = re.compile(
    r'<img[^>]+class="post-thumb"[^>]*src="([^"]+)"', re.I
)
EXCERPT_PATTERN = re.compile(
    r'<div\s+class="post-excerpt">\s*(.*?)\s*</div>', re.S
)


def parse_list_page(html: str, zhihai_class: str) -> list:
    """解析一个列表页，返回图书记录列表"""
    items = []
    for m in LI_PATTERN.finditer(html):
        book_id = m.group(1)
        block = m.group(2)

        title_m = TITLE_PATTERN.search(block)
        title = title_m.group(1).strip() if title_m else ""
        title = re.sub(r"<[^>]+>", "", title).strip()

        cover_m = COVER_PATTERN.search(block)
        cover_url = cover_m.group(1).strip() if cover_m else ""

        excerpt_m = EXCERPT_PATTERN.search(block)
        excerpt = excerpt_m.group(1).strip() if excerpt_m else ""
        excerpt = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", excerpt)).strip()

        if not title or not book_id:
            continue

        items.append({
            "book_id": book_id,
            "title": title,
            "cover_url": cover_url,
            "excerpt": excerpt,
            "zhihai_class": zhihai_class,
            "source_list": f"zhongtu-{zhihai_class}",
        })
    return items


def crawl_one_class(zhihai_class: str, target_top: str, quota: int, seen_ids: set) -> list:
    """爬单个中图法大类直到满足配额"""
    print(f"\n>>> 爬 zhongtu-{zhihai_class} (目标一级={target_top}, 配额={quota})")
    collected = []
    page = 1
    empty_pages = 0

    while len(collected) < quota and page <= ZHIHAI_MAX_PAGES_PER_CLASS:
        url = ZHIHAI_LIST_URL_PATTERN.format(cls=zhihai_class, page=page)
        try:
            html = fetch_page(url)
        except RuntimeError as e:
            print(f"  页 {page} 抓取失败，跳过: {e}")
            page += 1
            empty_pages += 1
            if empty_pages >= 3:
                break
            continue

        items = parse_list_page(html, zhihai_class)
        if not items:
            print(f"  页 {page} 无图书条目，结束")
            break

        # 去重 + 分类校验
        added = 0
        for it in items:
            if it["book_id"] in seen_ids:
                continue
            # 校验该图书是否属于目标一级分类（KID 特殊：I 文学中含童书关键词归 KID）
            top_code, third_id = classify_book(it["title"], it["excerpt"], zhihai_class)
            if top_code != target_top:
                continue
            it["top_code"] = top_code
            it["third_category_id"] = third_id
            seen_ids.add(it["book_id"])
            collected.append(it)
            added += 1
            if len(collected) >= quota:
                break

        print(f"  页 {page}: 解析 {len(items)} 本，新增 {added} 本，累计 {len(collected)}/{quota}")
        page += 1
        empty_pages = 0
        sleep_jitter(REQUEST_DELAY_MIN, REQUEST_DELAY_MAX)

    print(f"<<< zhongtu-{zhihai_class} 完成，共 {len(collected)} 本")
    return collected


def crawl_all() -> list:
    """按配额爬所有一级分类对应的中图法大类"""
    seen_ids = set()
    all_books = []

    # KID 单独处理：从 I 文学池里筛童书关键词（在 LIT 之后爬，已去重）
    order = ["LIT", "HIS", "SCI", "SOC", "ART", "KID"]

    for top in order:
        quota = CATEGORY_QUOTA.get(top, 0)
        if quota <= 0:
            continue
        sources = ZHIHAI_CATEGORY_SOURCES.get(top, [])
        if not sources:
            print(f"[skip] {top} 无来源大类")
            continue

        # 配额按大类均分
        per_class_quota = max(1, quota // len(sources))
        remainder = quota - per_class_quota * len(sources)

        for i, cls in enumerate(sources):
            q = per_class_quota + (remainder if i == 0 else 0)
            batch = crawl_one_class(cls, top, q, seen_ids)
            all_books.extend(batch)

    return all_books


def main() -> None:
    print(f"=== 知海图书爬虫启动 ===")
    print(f"DEBUG_MODE={DEBUG_MODE}, 总配额={sum(CATEGORY_QUOTA.values())}")

    books = crawl_all()
    print(f"\n=== 抓取完成：共 {len(books)} 本 ===")

    # 按一级分类统计
    stats = {}
    for b in books:
        top = b.get("top_code", "?")
        stats[top] = stats.get(top, 0) + 1
    for top, cnt in sorted(stats.items()):
        print(f"  {top}: {cnt} 本")

    BOOKS_RAW_FILE.parent.mkdir(parents=True, exist_ok=True)
    BOOKS_RAW_FILE.write_text(
        json.dumps(books, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"\n原始数据已保存：{BOOKS_RAW_FILE}")


if __name__ == "__main__":
    main()
