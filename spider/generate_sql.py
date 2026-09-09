"""
SQL 生成脚本

输入：spider/data/books_final.json（已补全字段 + 已下载封面）
输出：
  - backend/src/main/resources/db/patch_categories_v2.sql  全量替换分类树（61 节点）
  - backend/src/main/resources/db/patch_books_bulk.sql     INSERT IGNORE 批量图书

依赖：books 表 ISBN 唯一约束 → 用 INSERT IGNORE 避免重复
     patch_*.sql 由后端 DataInitializer 启动时自动执行
"""
import json
from pathlib import Path

from config import (
    BOOKS_FINAL_FILE,
    SQL_BOOKS_FILE,
    SQL_CATEGORIES_FILE,
)
from category_mapping import CATEGORIES_TREE


def sql_escape(s: str) -> str:
    """SQL 字符串转义"""
    if s is None:
        return ""
    return str(s).replace("\\", "\\\\").replace("'", "''").replace("\n", " ").replace("\r", " ")


def gen_categories_sql() -> str:
    """生成全量替换分类树的 SQL（DELETE + INSERT 61 节点）"""
    lines = [
        "-- ===== 分类树全量替换（由 spider/generate_sql.py 自动生成）=====",
        "-- 6 一级 + 14 二级 + 41 三级 = 61 节点",
        "USE library_db;",
        "",
        "-- 先清空旧分类（含现有 11 节点），保留 id 顺序",
        "DELETE FROM categories WHERE id BETWEEN 1 AND 999;",
        "",
        "-- 重置 AUTO_INCREMENT 起始值",
        "ALTER TABLE categories AUTO_INCREMENT = 1;",
        "",
        "-- 插入新分类树",
        "INSERT INTO categories (id, parent_id, category_name, category_code, level, sort_order, status) VALUES",
    ]

    rows = []
    for cid, pid, name, code, level, sort_order in CATEGORIES_TREE:
        rows.append(
            f"  ({cid}, {pid}, '{sql_escape(name)}', '{sql_escape(code)}', {level}, {sort_order}, 1)"
        )
    lines.append(",\n".join(rows) + ";")
    lines.append("")
    return "\n".join(lines)


def gen_books_sql(books: list) -> str:
    """生成图书批量插入 SQL（INSERT IGNORE，避免 ISBN 重复失败）"""
    lines = [
        "-- ===== 图书批量插入（由 spider/generate_sql.py 自动生成）=====",
        f"-- 共 {len(books)} 本图书",
        "-- 使用 INSERT IGNORE：ISBN 重复时跳过",
        "USE library_db;",
        "",
        "INSERT IGNORE INTO books "
        "(title, author, publisher, isbn, category_id, location, price, publish_date, "
        "cover_url, description, total_count, available_count, status, create_time) VALUES",
    ]

    from datetime import datetime
    now_iso = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    rows = []
    for b in books:
        title = sql_escape(b.get("title", ""))
        author = sql_escape(b.get("author", "佚名"))
        publisher = sql_escape(b.get("publisher", ""))
        isbn = sql_escape(b.get("isbn", ""))
        category_id = b.get("category_id", 0) or 0
        location = sql_escape(b.get("location", ""))
        price = b.get("price", 35.00)
        publish_date = b.get("publish_date", "")
        # publish_date 严格校验：只接受 YYYY-MM-DD / YYYY-MM / YYYY 格式，否则 NULL
        pd_sql = "NULL"
        if publish_date:
            import re as _re
            if _re.match(r"^\d{4}-\d{1,2}-\d{1,2}$", publish_date) or \
               _re.match(r"^\d{4}-\d{1,2}$", publish_date) or \
               _re.match(r"^\d{4}$", publish_date):
                pd_sql = f"'{sql_escape(publish_date)}'"
        cover_url = sql_escape(b.get("cover_url", ""))
        description = sql_escape(b.get("description", ""))
        total_count = b.get("total_count", 3)
        available_count = total_count  # 初始可借 = 总馆藏
        # 截断超长 description
        if len(description) > 500:
            description = description[:498] + "…"

        rows.append(
            f"  ('{title}', '{author}', '{publisher}', '{isbn}', {category_id}, "
            f"'{location}', {price}, {pd_sql}, '{cover_url}', '{description}', "
            f"{total_count}, {available_count}, 1, '{now_iso}')"
        )

    lines.append(",\n".join(rows) + ";")
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    if not BOOKS_FINAL_FILE.exists():
        print(f"[error] 找不到 {BOOKS_FINAL_FILE}")
        return

    books = json.loads(BOOKS_FINAL_FILE.read_text(encoding="utf-8"))
    print(f"=== SQL 生成启动，共 {len(books)} 本图书 ===")

    # 统计分类分布
    stats = {}
    for b in books:
        top = b.get("top_code", "?")
        stats[top] = stats.get(top, 0) + 1
    print("分类分布：")
    for top, cnt in sorted(stats.items()):
        print(f"  {top}: {cnt} 本")

    # 检查 category_id 缺失
    missing_cat = [b for b in books if not b.get("category_id")]
    if missing_cat:
        print(f"[warn] {len(missing_cat)} 本 category_id 缺失，将用 0 占位")

    # 生成分类 SQL
    cat_sql = gen_categories_sql()
    SQL_CATEGORIES_FILE.parent.mkdir(parents=True, exist_ok=True)
    SQL_CATEGORIES_FILE.write_text(cat_sql, encoding="utf-8")
    print(f"\n分类 SQL 已生成：{SQL_CATEGORIES_FILE}")

    # 生成图书 SQL
    books_sql = gen_books_sql(books)
    SQL_BOOKS_FILE.parent.mkdir(parents=True, exist_ok=True)
    SQL_BOOKS_FILE.write_text(books_sql, encoding="utf-8")
    print(f"图书 SQL 已生成：{SQL_BOOKS_FILE}")

    # 输出关键约束提醒
    print("\n=== 后续步骤 ===")
    print(f"1. 启动后端时 DataInitializer 会自动执行 patch_*.sql")
    print(f"2. books 表 ISBN 重复会被 INSERT IGNORE 跳过")
    print(f"3. 封面已在 {Path('backend/uploads/covers').absolute()}")
    print(f"4. cover_url 已对齐 /uploads/covers/ 路径，前端可直接加载")


if __name__ == "__main__":
    main()
