"""
知海图书封面下载

输入：spider/data/books_final.json（含 remote_cover_url 直链 + 本地 cover_url 路径）
输出：backend/uploads/covers/{book_id}.jpg
"""
import json
import random
import time
from pathlib import Path

import requests

from config import (
    BOOKS_FINAL_FILE,
    COVER_DELAY_MAX,
    COVER_DELAY_MIN,
    COVERS_DIR,
    MAX_RETRIES,
    REQUEST_TIMEOUT,
    RETRY_DELAY,
    USER_AGENTS,
)


def get_ua() -> str:
    return random.choice(USER_AGENTS)


def sleep_jitter(min_s: float, max_s: float) -> None:
    time.sleep(random.uniform(min_s, max_s))


def download_one(url: str, dest: Path) -> bool:
    """下载单个封面到 dest，返回是否成功"""
    if dest.exists() and dest.stat().st_size > 1024:
        return True  # 已存在且有效，跳过

    last_err = None
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            r = requests.get(
                url,
                headers={
                    "User-Agent": get_ua(),
                    "Accept": "image/webp,image/apng,image/*,*/*;q=0.8",
                    "Accept-Language": "zh-CN,zh;q=0.9",
                    "Referer": "https://www.zhihailib.com/",
                    "Connection": "close",
                },
                timeout=REQUEST_TIMEOUT,
                stream=True,
            )
            if r.status_code == 200 and "image" in r.headers.get("Content-Type", ""):
                dest.parent.mkdir(parents=True, exist_ok=True)
                with dest.open("wb") as f:
                    for chunk in r.iter_content(chunk_size=8192):
                        if chunk:
                            f.write(chunk)
                size = dest.stat().st_size
                if size > 1024:
                    return True
                else:
                    dest.unlink(missing_ok=True)
            print(f"  [warn] {url} 状态={r.status_code} CT={r.headers.get('Content-Type')} 第{attempt}次")
        except requests.RequestException as e:
            last_err = e
            print(f"  [warn] {url} 异常={e} 第{attempt}次")
        sleep_jitter(RETRY_DELAY, RETRY_DELAY * 1.5)
    return False


def main() -> None:
    if not BOOKS_FINAL_FILE.exists():
        print(f"[error] 找不到 {BOOKS_FINAL_FILE}")
        return

    books = json.loads(BOOKS_FINAL_FILE.read_text(encoding="utf-8"))
    print(f"=== 封面下载启动，共 {len(books)} 本 ===")

    ok = 0
    fail = 0
    fail_list = []
    for i, b in enumerate(books, 1):
        remote = b.get("remote_cover_url", "")
        if not remote:
            print(f"[{i}/{len(books)}] {b['book_id']} 无封面直链，跳过")
            fail += 1
            fail_list.append(b["book_id"])
            continue

        dest = COVERS_DIR / f"{b['book_id']}.jpg"
        print(f"[{i}/{len(books)}] {b['book_id']}.jpg <- {remote[:60]}")
        if download_one(remote, dest):
            ok += 1
        else:
            fail += 1
            fail_list.append(b["book_id"])
        sleep_jitter(COVER_DELAY_MIN, COVER_DELAY_MAX)

    print(f"\n=== 下载完成 ===")
    print(f"成功 {ok} / 失败 {fail} / 总 {len(books)}")
    if fail_list:
        print(f"失败列表：{fail_list}")
        # 在 books_final.json 中标记封面失败的，cover_url 置空（前端会兜底显示书名首字）
        for b in books:
            if b["book_id"] in fail_list:
                b["cover_url"] = ""
        BOOKS_FINAL_FILE.write_text(
            json.dumps(books, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print("已更新 books_final.json：封面失败项的 cover_url 置空")


if __name__ == "__main__":
    main()
