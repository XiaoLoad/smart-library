"""
豆瓣 → 本地三级分类映射

设计原则：
1. 本地分类树 = 6 一级 + 14 二级 + 30 三级 = 50 节点
2. 每个三级分类对应一个或多个豆瓣 tag（关键词匹配）
3. 爬虫按"一级分类配额"取样，但每个 tag 在豆瓣侧独立分页爬取
"""
from config import CATEGORY_QUOTA

# ===== 本地三级分类树定义 =====
# 格式: (id, parent_id, name, code, level, sort_order)
# id 设计：1位一级 + 2位二级 + 3位三级（共6位数字，便于人工阅读）
CATEGORIES_TREE = [
    # ===== 一级（6 个）=====
    (1,    0,   "文学",        "LIT",     1, 1),
    (2,    0,   "历史·哲学",   "HIS",     1, 2),
    (3,    0,   "科学·技术",   "SCI",     1, 3),
    (4,    0,   "社科·经管",   "SOC",     1, 4),
    (5,    0,   "艺术·生活",   "ART",     1, 5),
    (6,    0,   "童书·青少年", "KID",     1, 6),

    # ===== 文学 二级（3）+ 三级（10）=====
    (11,   1,   "中国文学",    "LIT-CN",  2, 1),
    (111,  11,  "古典文学",    "LIT-CN-CLS", 3, 1),
    (112,  11,  "现代文学",    "LIT-CN-MOD", 3, 2),   # 五四至建国前后
    (113,  11,  "当代文学",    "LIT-CN-CUR", 3, 3),   # 当代小说
    (114,  11,  "散文随笔",    "LIT-CN-ESY", 3, 4),
    (12,   1,   "外国文学",    "LIT-FG",     2, 2),
    (121,  12,  "欧美文学",    "LIT-FG-EU",  3, 1),
    (122,  12,  "日韩文学",    "LIT-FG-JK",  3, 2),
    (123,  12,  "其他外国文学","LIT-FG-OT",  3, 3),
    (13,   1,   "类型小说",    "LIT-GEN",    2, 3),
    (131,  13,  "科幻奇幻",    "LIT-GEN-SF", 3, 1),
    (132,  13,  "推理悬疑",    "LIT-GEN-MY", 3, 2),
    (133,  13,  "武侠小说",    "LIT-GEN-WX", 3, 3),

    # ===== 历史·哲学 二级（4）+ 三级（6）=====
    (21,   2,   "中国历史",    "HIS-CN",     2, 1),
    (211,  21,  "通史断代",    "HIS-CN-GEN", 3, 1),
    (212,  21,  "历史人物",    "HIS-CN-PER", 3, 2),
    (22,   2,   "世界历史",    "HIS-WD",     2, 2),
    (221,  22,  "世界通史",    "HIS-WD-GEN", 3, 1),
    (222,  22,  "国别史",      "HIS-WD-REG", 3, 2),
    (23,   2,   "中国哲学",    "PHI-CN",     2, 3),
    (231,  23,  "传统思想",    "PHI-CN-TRD", 3, 1),
    (24,   2,   "西方哲学",    "PHI-FG",     2, 4),
    (241,  24,  "古典哲学",    "PHI-FG-CLS", 3, 1),
    (242,  24,  "近现代哲学",  "PHI-FG-MOD", 3, 2),

    # ===== 科学·技术 二级（3）+ 三级（9）=====
    (31,   3,   "自然科学",    "SCI-NT",     2, 1),
    (311,  31,  "物理与宇宙",  "SCI-NT-PHY", 3, 1),
    (312,  31,  "生命科学",    "SCI-NT-BIO", 3, 2),
    (313,  31,  "数学与综合",  "SCI-NT-MAT", 3, 3),
    (32,   3,   "计算机科学",  "SCI-CS",     2, 2),
    (321,  32,  "编程语言",    "SCI-CS-PL",  3, 1),
    (322,  32,  "算法与数据结构","SCI-CS-ALG",3, 2),
    (323,  32,  "人工智能",    "SCI-CS-AI",  3, 3),
    (324,  32,  "软件工程",    "SCI-CS-SE",  3, 4),
    (33,   3,   "工业技术",    "SCI-IT",     2, 3),
    (331,  33,  "工程技术",    "SCI-IT-EN",  3, 1),

    # ===== 社科·经管 二级（4）+ 三级（5）=====
    (41,   4,   "社会学",      "SOC-SOC",    2, 1),
    (411,  41,  "社会与文化",  "SOC-SOC-CUL",3, 1),
    (42,   4,   "经济管理",    "SOC-ECO",    2, 2),
    (421,  42,  "经济学",      "SOC-ECO-ECO",3, 1),
    (422,  42,  "管理学",      "SOC-ECO-MGT",3, 2),
    (43,   4,   "心理学",      "SOC-PSY",    2, 3),
    (431,  43,  "心理学",      "SOC-PSY-GEN",3, 1),
    (44,   4,   "教育学",      "SOC-EDU",    2, 4),
    (441,  44,  "教育学",      "SOC-EDU-GEN",3, 1),

    # ===== 艺术·生活 二级（2）+ 三级（5）=====
    (51,   5,   "艺术",        "ART-ART",    2, 1),
    (511,  51,  "绘画与摄影",  "ART-ART-VIS",3, 1),
    (512,  51,  "音乐",        "ART-ART-MUS",3, 2),
    (513,  51,  "影视戏剧",    "ART-ART-FIL",3, 3),
    (52,   5,   "生活美学",    "ART-LIFE",   2, 2),
    (521,  52,  "生活美学",    "ART-LIFE-GEN",3, 1),

    # ===== 童书·青少年 二级（2）+ 三级（3）=====
    (61,   6,   "儿童绘本",    "KID-PIC",    2, 1),
    (611,  61,  "儿童绘本",    "KID-PIC-GEN",3, 1),
    (62,   6,   "青少年文学",  "KID-TEEN",   2, 2),
    (621,  62,  "青少年文学",  "KID-TEEN-GEN",3, 1),
    (622,  62,  "儿童文学",    "KID-TEEN-CHL",3, 2),
]

# 验证总数: 6 一级 + 14 二级 + 41 三级 = 61
assert len(CATEGORIES_TREE) == 61, f"分类树节点数错误: {len(CATEGORIES_TREE)}"

# ===== 豆瓣 tag → 本地三级分类 ID 映射 =====
# 豆瓣 tag 是关键词，多条 tag 可能映射到同一个本地三级分类
# 同一个一级分类下，多个 tag 共同凑齐该一级分类的配额
# 格式: "豆瓣tag": (category_id_top, category_id_third)
DOUBAN_TAG_TO_CATEGORY = {
    # ===== 文学 LIT =====
    # 中国文学
    "中国文学":     ("LIT", 113),
    "当代文学":     ("LIT", 113),
    "现代文学":     ("LIT", 112),
    "古典文学":     ("LIT", 111),
    "散文":         ("LIT", 114),
    "随笔":         ("LIT", 114),
    "诗歌":         ("LIT", 114),
    # 外国文学
    "外国文学":     ("LIT", 121),
    "欧美文学":     ("LIT", 121),
    "日本文学":     ("LIT", 122),
    "日本推理":     ("LIT", 132),  # 日系推理归到推理
    "韩国文学":     ("LIT", 122),
    "拉美文学":     ("LIT", 123),
    "俄国文学":     ("LIT", 121),
    "法国文学":     ("LIT", 121),
    "英国文学":     ("LIT", 121),
    "美国文学":     ("LIT", 121),
    # 类型小说
    "科幻":         ("LIT", 131),
    "奇幻":         ("LIT", 131),
    "推理":         ("LIT", 132),
    "悬疑":         ("LIT", 132),
    "武侠":         ("LIT", 133),
    "小说":         ("LIT", 113),  # 通用小说倾向当代

    # ===== 历史·哲学 HIS =====
    "历史":         ("HIS", 211),
    "中国历史":     ("HIS", 211),
    "世界史":       ("HIS", 221),
    "世界历史":     ("HIS", 221),
    "全球史":       ("HIS", 221),
    "传记":         ("HIS", 212),
    "人物传记":     ("HIS", 212),
    "古代史":       ("HIS", 211),
    "近代史":       ("HIS", 211),
    # 哲学
    "哲学":         ("HIS", 241),
    "中国哲学":     ("HIS", 231),
    "国学":         ("HIS", 231),
    "儒家":         ("HIS", 231),
    "道家":         ("HIS", 231),
    "西方哲学":     ("HIS", 241),
    "古希腊哲学":   ("HIS", 241),
    "存在主义":     ("HIS", 242),
    "现象学":       ("HIS", 242),

    # ===== 科学·技术 SCI =====
    # 自然科学
    "科普":         ("SCI", 313),
    "物理":         ("SCI", 311),
    "量子物理":     ("SCI", 311),
    "宇宙学":       ("SCI", 311),
    "天文":         ("SCI", 311),
    "天文学":       ("SCI", 311),
    "生物":         ("SCI", 312),
    "生物学":       ("SCI", 312),
    "进化论":       ("SCI", 312),
    "数学":         ("SCI", 313),
    "科学史":       ("SCI", 313),
    # 计算机科学
    "计算机":       ("SCI", 321),
    "编程":         ("SCI", 321),
    "Python":       ("SCI", 321),
    "Java":         ("SCI", 321),
    "C语言":        ("SCI", 321),
    "JavaScript":   ("SCI", 321),
    "算法":         ("SCI", 322),
    "数据结构":     ("SCI", 322),
    "人工智能":     ("SCI", 323),
    "机器学习":     ("SCI", 323),
    "深度学习":     ("SCI", 323),
    "神经网络":     ("SCI", 323),
    "软件工程":     ("SCI", 324),
    "敏捷开发":     ("SCI", 324),
    "测试":         ("SCI", 324),
    "互联网":       ("SCI", 324),
    # 工业技术
    "工程":         ("SCI", 331),
    "工业":         ("SCI", 331),

    # ===== 社科·经管 SOC =====
    "社会学":       ("SOC", 411),
    "人类学":       ("SOC", 411),
    "文化":         ("SOC", 411),
    "政治学":       ("SOC", 411),
    "政治":         ("SOC", 411),
    "经济学":       ("SOC", 421),
    "经济":         ("SOC", 421),
    "金融":         ("SOC", 421),
    "投资":         ("SOC", 421),
    "管理":         ("SOC", 422),
    "管理学":       ("SOC", 422),
    "创业":         ("SOC", 422),
    "领导力":       ("SOC", 422),
    "心理学":       ("SOC", 431),
    "认知心理学":   ("SOC", 431),
    "社会心理学":   ("SOC", 431),
    "教育":         ("SOC", 441),
    "教育学":       ("SOC", 441),
    "教育心理学":   ("SOC", 441),

    # ===== 艺术·生活 ART =====
    "艺术":         ("ART", 511),
    "绘画":         ("ART", 511),
    "摄影":         ("ART", 511),
    "设计":         ("ART", 511),
    "建筑":         ("ART", 511),
    "音乐":         ("ART", 512),
    "古典音乐":     ("ART", 512),
    "电影":         ("ART", 513),
    "戏剧":         ("ART", 513),
    "动画":         ("ART", 513),
    "生活":         ("ART", 521),
    "旅行":         ("ART", 521),
    "美食":         ("ART", 521),
    "手作":         ("ART", 521),

    # ===== 童书·青少年 KID =====
    "绘本":         ("KID", 611),
    "童书":         ("KID", 611),
    "儿童文学":     ("KID", 622),
    "青少年":       ("KID", 621),
    "童话":         ("KID", 622),
    "寓言":         ("KID", 622),
}

# ===== 按"一级分类"分组的 tag 抓取列表（用于配额控制）=====
# 同一一级分类下多个 tag 共同凑齐 quota
TAGS_BY_TOP_CATEGORY = {
    "LIT": [
        "现代文学", "当代文学", "古典文学", "散文",
        "外国文学", "日本文学", "俄国文学", "拉美文学",
        "科幻", "推理", "武侠",
    ],
    "HIS": [
        "中国历史", "世界历史", "传记",
        "中国哲学", "西方哲学", "存在主义",
    ],
    "SCI": [
        "科普", "物理", "天文", "生物", "数学",
        "编程", "算法", "人工智能", "软件工程", "Python",
    ],
    "SOC": [
        "社会学", "人类学", "政治学",
        "经济学", "金融", "管理学",
        "心理学",
        "教育学",
    ],
    "ART": [
        "绘画", "摄影", "音乐", "电影", "设计",
        "生活", "旅行",
    ],
    "KID": [
        "绘本", "儿童文学", "童话",
    ],
}


def get_third_category_id(tag: str) -> int:
    """根据豆瓣 tag 返回本地三级分类 ID，找不到时返回 0 表示未分类"""
    mapping = DOUBAN_TAG_TO_CATEGORY.get(tag)
    return mapping[1] if mapping else 0


def get_top_category_code(tag: str) -> str:
    """根据豆瓣 tag 返回一级分类代码，找不到时返回空"""
    mapping = DOUBAN_TAG_TO_CATEGORY.get(tag)
    return mapping[0] if mapping else ""


def get_top_category_for_quota() -> dict:
    """返回"一级分类代码 → 配额"映射"""
    return CATEGORY_QUOTA.copy()


# ===== 知海中图法大类 → 本地一级分类 =====
ZHONGTU_CLASS_TO_TOP = {
    "i": "LIT",  # 文学
    "k": "HIS",  # 历史、地理
    "b": "HIS",  # 哲学、宗教
    "t": "SCI",  # 工业技术
    "o": "SCI",  # 数理科学和化学
    "q": "SCI",  # 生物科学
    "n": "SCI",  # 自然科学总论
    "c": "SOC",  # 社会科学总论
    "d": "SOC",  # 政治、法律
    "f": "SOC",  # 经济
    "g": "SOC",  # 文化、科学、教育、体育
    "j": "ART",  # 艺术
}


def classify_book(title: str, summary: str, zhihai_class: str) -> tuple:
    """
    基于书名+摘要+中图法大类，返回 (top_code, third_category_id)。
    策略：先按中图法大类定一级分类，再用书名/摘要关键词细化到三级。
    """
    text = (title or "") + " " + (summary or "")
    top = ZHONGTU_CLASS_TO_TOP.get(zhihai_class.lower(), "")
    if not top:
        return ("", 0)

    # 童书关键词优先：I 文学中含童书关键词的归 KID
    # 注意：用更严格组合，避免"青少年"等宽泛词误命（如《少年阿宾》）
    if "绘本" in text or "童话" in text or "寓言" in text:
        if "绘本" in text:
            return ("KID", 611)
        return ("KID", 622)
    if "儿童文学" in text or "少儿" in text or "童书" in text:
        return ("KID", 622)
    if "亲子" in text or "宝宝" in text:
        return ("KID", 621)

    # 按一级分类做关键词细化
    if top == "LIT":
        if any(k in text for k in ["诗", "词", "古文", "古典"]):
            return ("LIT", 111)
        if any(k in text for k in ["散", "随笔", "杂文"]):
            return ("LIT", 114)
        if any(k in text for k in ["日本", "韩国", "日韩"]):
            return ("LIT", 122)
        if any(k in text for k in ["欧", "美", "英", "法", "俄", "德", "拉美"]):
            return ("LIT", 121)
        if any(k in text for k in ["科幻", "奇幻"]):
            return ("LIT", 131)
        if any(k in text for k in ["推理", "悬疑", "侦探"]):
            return ("LIT", 132)
        if "武侠" in text:
            return ("LIT", 133)
        if any(k in text for k in ["现代", "当代", "小说"]):
            return ("LIT", 113)
        return ("LIT", 113)

    if top == "HIS":
        if zhihai_class.lower() == "b":
            # 哲学
            if any(k in text for k in ["中国", "儒", "道", "国", "佛", "禅"]):
                return ("HIS", 231)
            if any(k in text for k in ["存在", "现象", "现代", "后现代"]):
                return ("HIS", 242)
            return ("HIS", 241)
        # 历史
        if any(k in text for k in ["传记", "人物", "回忆录"]):
            return ("HIS", 212)
        if any(k in text for k in ["世界", "全球", "欧洲", "美国", "日本史", "国别"]):
            return ("HIS", 221)
        return ("HIS", 211)

    if top == "SCI":
        if any(k in text for k in ["编程", "Python", "Java", "C语言", "JavaScript", "Go语言", "Rust"]):
            return ("SCI", 321)
        if any(k in text for k in ["算法", "数据结构"]):
            return ("SCI", 322)
        if any(k in text for k in ["人工智能", "机器学习", "深度学习", "神经网络", "AI"]):
            return ("SCI", 323)
        if any(k in text for k in ["软件工程", "敏捷", "测试", "DevOps"]):
            return ("SCI", 324)
        if any(k in text for k in ["物理", "宇宙", "天文", "量子", "相对论"]):
            return ("SCI", 311)
        if any(k in text for k in ["生物", "进化", "基因", "细胞"]):
            return ("SCI", 312)
        if any(k in text for k in ["数学", "几何", "代数", "统计"]):
            return ("SCI", 313)
        return ("SCI", 331)

    if top == "SOC":
        if any(k in text for k in ["经济", "金融", "投资", "货币", "财政"]):
            return ("SOC", 421)
        if any(k in text for k in ["管理", "创业", "领导", "组织"]):
            return ("SOC", 422)
        if any(k in text for k in ["心理", "认知", "情绪"]):
            return ("SOC", 431)
        if any(k in text for k in ["教育", "教学", "学习"]):
            return ("SOC", 441)
        return ("SOC", 411)

    if top == "ART":
        if any(k in text for k in ["音乐", "乐", "曲"]):
            return ("ART", 512)
        if any(k in text for k in ["电影", "戏剧", "动画", "影视"]):
            return ("ART", 513)
        if any(k in text for k in ["生活", "旅行", "美食", "手作", "摄影"]):
            return ("ART", 521)
        return ("ART", 511)

    return (top, 0)


if __name__ == "__main__":
    # 自检：列出每个一级分类下的 tag 数量
    print("=== 分类映射自检 ===")
    for top, tags in TAGS_BY_TOP_CATEGORY.items():
        print(f"{top} ({CATEGORY_QUOTA[top]} 本) <- {len(tags)} 个豆瓣 tag: {tags}")
    print(f"\n总配额: {sum(CATEGORY_QUOTA.values())} 本")
    print(f"分类树节点数: {len(CATEGORIES_TREE)}")
    print("\n=== 知海中图法大类映射自检 ===")
    for cls, top in ZHONGTU_CLASS_TO_TOP.items():
        print(f"  zhongtu-{cls} -> {top}")
    # 几条样例
    samples = [
        ("明朝那些事儿", "当年明月 历史", "k"),
        ("三体", "刘慈欣 科幻", "i"),
        ("Python编程从入门到实践", "编程 入门", "t"),
        ("小王子", "童话 儿童", "i"),
        ("西方哲学史", "罗素 哲学", "b"),
    ]
    print("\n=== classify_book 样例 ===")
    for t, s, c in samples:
        print(f"  [{c}] {t} -> {classify_book(t, s, c)}")
