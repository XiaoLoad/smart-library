"""
爬虫全局配置
- UA 池、请求延迟、重试、配额
- 数据库与 SQL 落地路径
"""
import os
from pathlib import Path

# ===== 路径配置 =====
# 项目根目录（spider 的上一级）
PROJECT_ROOT = Path(__file__).resolve().parent.parent
SPIDER_DIR = Path(__file__).resolve().parent
DATA_DIR = SPIDER_DIR / "data"
DATA_DIR.mkdir(exist_ok=True)

# 中间/最终 JSON 数据文件
BOOKS_RAW_FILE = DATA_DIR / "books_raw.json"
BOOKS_FINAL_FILE = DATA_DIR / "books_final.json"

# 后端资源目录（自动生成 SQL 和封面落地）
BACKEND_ROOT = PROJECT_ROOT / "backend"
DB_DIR = BACKEND_ROOT / "src" / "main" / "resources" / "db"
COVERS_DIR = BACKEND_ROOT / "uploads" / "covers"
COVERS_DIR.mkdir(parents=True, exist_ok=True)

# 最终 SQL 输出文件
SQL_CATEGORIES_FILE = DB_DIR / "patch_categories_v2.sql"
SQL_BOOKS_FILE = DB_DIR / "patch_books_bulk.sql"

# 封面 URL 前缀（后端 context-path=/api + WebMvcConfig 的 /uploads/** 静态映射）
# 必须带 /api 前缀：前端 img src 经 Vite 代理 /api → 后端，FileController 上传返回值也用 /api/uploads/
COVER_URL_PREFIX = "/api/uploads/covers/"

# ===== 爬取配置 =====
# User-Agent 池（轮换使用，降低被识别概率）
USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
]

# 请求延迟（秒）：知海反爬弱，DEBUG 测试无封禁，全量适度降低
REQUEST_DELAY_MIN = 0.6
REQUEST_DELAY_MAX = 1.8

# 封面下载延迟（秒）：图片接口反爬较弱，可稍快
COVER_DELAY_MIN = 0.2
COVER_DELAY_MAX = 0.6

# 单页请求失败重试次数
MAX_RETRIES = 3

# 重试间隔（秒）
RETRY_DELAY = 2.0

# 请求超时（秒）
REQUEST_TIMEOUT = 15

# ===== 配额控制 =====
# 总目标 500 本，按一级分类分配
CATEGORY_QUOTA = {
    "LIT": 110,    # 文学
    "HIS": 90,     # 历史·哲学
    "SCI": 110,    # 科学·技术
    "SOC": 90,     # 社科·经管
    "ART": 50,     # 艺术·生活
    "KID": 50,     # 童书
}
TARGET_TOTAL = sum(CATEGORY_QUOTA.values())  # 500

# ===== 数据约束 =====
# 图书价格硬约束（与后端 @DecimalMin(10) 对齐）
MIN_PRICE = 10.0
DEFAULT_PRICE = 35.0

# description 字段最大长度（与数据库 VARCHAR(500) 对齐）
MAX_DESC_LENGTH = 500

# 馆藏数量随机范围
TOTAL_COUNT_MIN = 2
TOTAL_COUNT_MAX = 8

# 馆藏位置随机模板
LOCATION_TEMPLATES = [
    "A区{row}排{shelf}架",
    "B区{row}排{shelf}架",
    "C区{row}排{shelf}架",
    "D区{row}排{shelf}架",
]

# 爬取来源站点（知海检索：WordPress 站点，列表+详情页字段完整）
ZHIHAI_BASE = "https://www.zhihailib.com"
# 列表页 URL 模板：/book/zhongtu-{中图法字母}/page/{页码}
ZHIHAI_LIST_URL_PATTERN = ZHIHAI_BASE + "/book/zhongtu-{cls}/page/{page}"
# 详情页 URL 模板：/book/{id}
ZHIHAI_DETAIL_URL_PATTERN = ZHIHAI_BASE + "/book/{book_id}"

# ===== 一级分类 → 知海中图法大类来源 =====
# 知海按中图法大类组织图书，每个大类有 700+ 页 × 25 本 = 17500+ 本
# 这里指定每个本地一级分类从哪些中图法大类取样
# 中图法字母：I 文学 / K 历史 / B 哲学 / T 工业技术 / O 数理化 / Q 生物 / N 自科
#             C 社科 / D 政治 / F 经济 / G 文教 / J 艺术
ZHIHAI_CATEGORY_SOURCES = {
    "LIT": ["i"],                              # 文学
    "HIS": ["k", "b"],                         # 历史、地理 + 哲学、宗教
    "SCI": ["t", "o", "q", "n"],               # 工业技术 + 数理化 + 生物 + 自科总论
    "SOC": ["c", "d", "f", "g"],               # 社科 + 政治 + 经济 + 文教
    "ART": ["j"],                              # 艺术
    "KID": ["i"],                              # 童书从 I 文学里按关键词筛选
}

# 童书关键词（用于从 I 文学池里筛 KID 配额）
KID_KEYWORDS = ["童", "绘本", "童话", "寓言", "儿童文学", "青少年", "少儿", "宝宝", "亲子"]

# 每页条目数（知海默认 25 本/页）
ZHIHAI_PAGE_SIZE = 25

# 单个中图法大类最大翻页深度（避免无限翻页）
ZHIHAI_MAX_PAGES_PER_CLASS = 30

# 当下小批量验证：把 quota 等比例缩到 30 本
DEBUG_MODE = os.environ.get("SPIDER_DEBUG") == "1"
if DEBUG_MODE:
    # 缩到约 30 本的样例配额
    CATEGORY_QUOTA = {
        "LIT": 7,
        "HIS": 5,
        "SCI": 7,
        "SOC": 5,
        "ART": 3,
        "KID": 3,
    }
    TARGET_TOTAL = sum(CATEGORY_QUOTA.values())
