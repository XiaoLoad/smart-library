"""
SQL 应用脚本：用 mysql 命令行导入 patch_*.sql 到 library_db

用法：
    python spider/apply_sql.py
"""
import subprocess
import sys
from pathlib import Path

from config import SQL_BOOKS_FILE, SQL_CATEGORIES_FILE


def find_mysql() -> str:
    """尝试在常见路径找 mysql.exe"""
    # 1. PATH 中找
    import shutil
    mysql = shutil.which("mysql")
    if mysql:
        return mysql
    # 2. 常见安装位置
    candidates = [
        r"D:\MySQL\MySQL Server 8.0\bin\mysql.exe",
        r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
        r"D:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    ]
    for c in candidates:
        if Path(c).exists():
            return c
    return ""


def apply_sql(mysql_exe: str, sql_file: Path, db_user: str = "root", db_pwd: str = "REPLACE_DB_PASSWORD", db_name: str = "library_db") -> bool:
    """用 mysql 命令行导入 SQL 文件"""
    if not sql_file.exists():
        print(f"[error] SQL 文件不存在: {sql_file}")
        return False

    cmd = [mysql_exe, "-h", "127.0.0.1", f"-u{db_user}", f"-p{db_pwd}", db_name]
    print(f"应用 {sql_file.name} ...")
    try:
        with sql_file.open("r", encoding="utf-8") as f:
            result = subprocess.run(cmd, stdin=f, capture_output=True, text=True, timeout=120)
        if result.returncode != 0:
            print(f"[error] 失败：{result.stderr}")
            return False
        if result.stderr and "Warning" in result.stderr:
            # mysql 密码警告，可忽略
            pass
        print(f"  完成")
        return True
    except Exception as e:
        print(f"[error] 异常：{e}")
        return False


def main() -> None:
    mysql_exe = find_mysql()
    if not mysql_exe:
        print("[error] 找不到 mysql.exe，请确认 MySQL 安装路径或加入 PATH")
        sys.exit(1)
    print(f"使用 mysql: {mysql_exe}")

    # 先应用分类 SQL（重置分类树）
    if not apply_sql(mysql_exe, SQL_CATEGORIES_FILE):
        print("[fatal] 分类 SQL 应用失败，终止")
        sys.exit(2)

    # 再应用图书 SQL（INSERT IGNORE）
    if not apply_sql(mysql_exe, SQL_BOOKS_FILE):
        print("[fatal] 图书 SQL 应用失败，终止")
        sys.exit(3)

    # 查询结果
    print("\n=== 应用完成，查询统计 ===")
    cmd = [mysql_exe, "-h", "127.0.0.1", "-uroot", "-pREPLACE_DB_PASSWORD", "library_db", "-e",
           "SELECT COUNT(*) AS total_books FROM books; "
           "SELECT c.category_name, COUNT(b.id) AS cnt FROM books b JOIN categories c ON b.category_id=c.id GROUP BY c.id ORDER BY c.id;"]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        print(result.stdout)
        if result.stderr and "Warning" not in result.stderr:
            print("STDERR:", result.stderr)
    except Exception as e:
        print(f"查询失败: {e}")


if __name__ == "__main__":
    main()
