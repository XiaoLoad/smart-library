package com.library.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * AI 工具基类：持有会话用户身份与依赖上下文，提供统一的工具事件发射、
 * 数据快照与通用格式化能力。
 * <p>
 * 每次会话由 {@link AiService} 创建具体子类实例（{@link CommonTools} /
 * {@link ReaderTools} / {@link AdminTools}），将当前用户身份（userId/userType）
 * 烘焙进实例字段，避免在 langchain4j 异步线程中丢失
 * {@link com.library.security.CurrentUser}。
 * <p>
 * 工具调用过程通过 {@link ToolEventEmitter} 回调向前端 SSE 推送 tool 事件，
 * 用于在 UI 上展示「正在调用工具 / 工具返回」状态；end 阶段携带精简数据快照。
 */
public abstract class BaseLibraryTools {

    private static final Logger log = LoggerFactory.getLogger(BaseLibraryTools.class);

    /** 工具调用事件发射器（可选，未设置时不发送事件） */
    public interface ToolEventEmitter {
        /**
         * 发送工具调用事件。
         *
         * @param toolName 工具名称（如 queryBooks）
         * @param phase    阶段：start / end / error
         * @param detail   简短描述（如返回条数、错误信息）
         * @param data     结构化数据快照（JSON 字符串，仅 end 阶段非空；start/error 阶段为 null）
         */
        void emit(String toolName, String phase, String detail, String data);
    }

    /**
     * 授权请求回调：写工具被门禁拦截时，由它把授权请求推给前端。
     * <p>
     * 实现方（AiService）负责推 confirm SSE 事件并登记令牌。
     *
     * @param toolName 写工具名
     * @param title    授权卡标题（如「借阅《围城》」）
     * @param rows     授权卡参数明细（键值表，可为空）
     * @param args     工具参数（服务端保存，客户端仅拿到不透明令牌）
     */
    public interface ConfirmRequestHandler {
        void request(String toolName, String title, List<Map<String, String>> rows, Map<String, Object> args);
    }

    /** 数据快照最大字符数（避免 SSE 包过大） */
    private static final int DATA_SNAPSHOT_MAX_LEN = 600;
    /** 列表类快照保留的最大条目数（避免长列表爆包） */
    private static final int LIST_SNAPSHOT_LIMIT = 3;
    /** 检索类工具快照条数上限（queryBooks 的结果会驱动图书页面 AI 检索视图，需全量） */
    private static final int BOOK_SEARCH_SNAPSHOT_LIMIT = 15;
    /** 检索类工具快照独立字符上限（豁免全局 600 截断；15 条精简条目约 3~5KB） */
    private static final int BOOK_SEARCH_SNAPSHOT_MAX_LEN = 8000;
    /**
     * 看板类工具快照独立字符上限。
     * <p>
     * 统计概览类结果含分类分布、趋势、KPI 等多个数组，全局 600 上限会把它截成非法 JSON，
     * 前端解析失败只能回落到 JSON 兜底视图——「生成式 UI」最该发挥的看板场景反而失效。
     */
    private static final int DASHBOARD_SNAPSHOT_MAX_LEN = 12000;
    /**
     * 表格/卡片类工具快照字符上限。
     * <p>
     * 生成式 UI 升级后，所有映射到可视化 type 的快照都会被前端提升为一级内联卡片默认展开；
     * 借阅记录等表格 16 条会被 600 上限截成非法 JSON（前端解析失败、卡片不渲染），
     * 与看板同类问题——凡 SNAPSHOT_TYPES 命中的工具统一豁免全局截断。
     */
    private static final int GEN_UI_SNAPSHOT_MAX_LEN = 12000;
    /** 表格类快照条数上限（内联卡片一屏可见的合理行数） */
    private static final int TABLE_SNAPSHOT_LIMIT = 20;

    protected final Long userId;
    protected final Integer userType;
    protected final AiToolContext ctx;

    /** 当前请求的工具事件发射器，由 AiService 在创建工具实例时注入 */
    private ToolEventEmitter eventEmitter;

    /** 当前请求的写操作授权回调，由 AiService 注入；为 null 时写操作不拦截（退化行为） */
    private ConfirmRequestHandler confirmHandler;

    /** 当前请求的权限档位，由 AiService 按会话配置注入；默认逐笔确认 */
    private PermissionMode permissionMode = PermissionMode.CONFIRM;

    /** 批量授权收集器（batch 档位用）：本轮被拦截的写操作在此累积，由 AiService 在流结束前统一弹卡 */
    public interface BatchCollector {
        /**
         * 收集一条待授权的写操作。
         *
         * @param toolName 写工具名
         * @param title    授权卡内该项标题（如「取书 BR202610280001」）
         * @param rows     该项参数明细
         * @param args     工具参数（服务端保存）
         */
        void collect(String toolName, String title, List<Map<String, String>> rows, Map<String, Object> args);
    }

    private BatchCollector batchCollector;

    protected BaseLibraryTools(Long userId, Integer userType, AiToolContext ctx) {
        this.userId = userId;
        this.userType = userType;
        this.ctx = ctx;
    }

    /** 注入本次请求的事件发射器 */
    public void setEventEmitter(ToolEventEmitter emitter) {
        this.eventEmitter = emitter;
    }

    /** 注入本次请求的写操作授权回调 */
    public void setConfirmHandler(ConfirmRequestHandler handler) {
        this.confirmHandler = handler;
    }

    /** 注入本次请求的权限档位 */
    public void setPermissionMode(PermissionMode mode) {
        this.permissionMode = mode == null ? PermissionMode.CONFIRM : mode;
    }

    /** 注入批量授权收集器（batch 档位） */
    public void setBatchCollector(BatchCollector collector) {
        this.batchCollector = collector;
    }

    protected boolean isAdmin() {
        return userType != null && userType == 3;
    }

    /**
     * 包裹<b>写操作</b>工具方法体：先过授权门禁，用户确认后才真正执行。
     * <p>
     * 与只读工具（{@link #withEvent}）的关键差异：
     * <ul>
     *   <li>未登记在 {@link AiToolRegistry#WRITE_TOOLS} 的写操作会被直接拒绝
     *       （fail-closed），不会退化为普通执行；</li>
     *   <li>写工具在执行前先查 {@link AiToolRegistry#WRITE_TOOLS}，命中则<b>不执行</b>，
     *       改为向 {@link ConfirmRequestHandler} 发起授权请求；</li>
     *   <li>被拦截时<b>不发 start 事件</b>——start 事件会触发前端 reset 清空正文，
     *       而授权请求不是工具执行，不能把用户已看到的文字清掉；</li>
     *   <li>被拦截时返回「等待用户确认」的结构化结果，模型据此知道该等用户点头，
     *       而不是以为操作已经完成。</li>
     * </ul>
     *
     * @param name       工具名（须在 AiToolRegistry.WRITE_TOOLS 中登记）
     * @param title      授权卡标题（如「借阅《围城》」）
     * @param rows       授权卡参数明细
     * @param args       工具参数（服务端保存，客户端只拿令牌）
     * @param action     真正的写操作逻辑，仅在用户确认后执行
     */
    protected Map<String, Object> withWriteEvent(String name, String title,
                                                 List<Map<String, String>> rows,
                                                 Map<String, Object> args,
                                                 Function<Void, Map<String, Object>> action) {
        // 白名单登记制：未登记的写操作一律拒绝执行（fail-closed），防止漏登记工具绕过授权。
        // 三档位共享此底线：登记表是「哪些操作算写操作」的定义，档位只决定「怎么确认」
        if (!AiToolRegistry.requiresConfirm(name)) {
            Map<String, Object> rejected = new LinkedHashMap<>();
            rejected.put("success", false);
            rejected.put("rejected", true);
            rejected.put("awaitingConfirm", false);
            rejected.put("message", "工具[" + name + "]未登记为写操作，已拒绝执行");
            return rejected;
        }
        // 自动执行档：跳过拦截，走普通执行路径（回执卡片/轨迹照常记录）
        if (permissionMode == PermissionMode.AUTO) {
            return withEvent(name, title, action);
        }
        // 批量授权档：不立即弹卡，累积到本轮批次，由 AiService 在流结束前合并成一张批量卡
        if (permissionMode == PermissionMode.BATCH) {
            if (batchCollector != null) {
                batchCollector.collect(name, title, rows, args);
            } else {
                // 收集器缺失（装配异常）退回逐笔确认，保持 fail-safe
                log.warn("[AI] 批量收集器未注入，写操作[{}]退回逐笔确认", name);
                return interceptForConfirm(name, title, rows, args);
            }
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("success", false);
            pending.put("awaitingConfirm", true);
            pending.put("batched", true);
            pending.put("message", "本操作已加入本轮批量授权清单，将在本轮结束前统一请求确认");
            return pending;
        }
        // 逐笔确认档（默认）：门禁未初始化视为装配异常，放行但留高可见日志
        if (confirmHandler == null) {
            log.error("[AI] 写门禁未初始化，写操作[{}]直接执行(fail-open)", name);
            return withEvent(name, title, action);
        }
        return interceptForConfirm(name, title, rows, args);
    }

    /**
     * 逐笔确认：立即弹授权卡，返回「等待用户确认」结构。
     */
    private Map<String, Object> interceptForConfirm(String name, String title,
                                                    List<Map<String, String>> rows,
                                                    Map<String, Object> args) {
        confirmHandler.request(name, title, rows, args);
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("success", false);
        pending.put("awaitingConfirm", true);
        pending.put("message", "已向用户发起授权请求，需用户确认后才会执行本操作");
        return pending;
    }

    /**
     * 包裹工具方法体，统一发射 start / end / error 事件。
     * end 阶段携带工具返回的精简数据快照（结构化 JSON）。
     */
    protected <R> R withEvent(String name, String startDetail, Function<Void, R> action) {
        if (eventEmitter != null) {
            eventEmitter.emit(name, "start", startDetail, null);
        }
        try {
            R r = action.apply(null);
            if (eventEmitter != null) {
                eventEmitter.emit(name, "end", describeResult(r), snapshotData(r, name));
            }
            return r;
        } catch (RuntimeException e) {
            if (eventEmitter != null) {
                eventEmitter.emit(name, "error", e.getMessage(), null);
            }
            throw e;
        }
    }

    /** 根据工具返回值生成简短的完成描述（卡片摘要展示用） */
    private String describeResult(Object r) {
        if (r instanceof Map<?, ?> m) {
            if (Boolean.TRUE.equals(m.get("success"))) {
                return "操作成功";
            }
            if (Boolean.FALSE.equals(m.get("success"))) {
                Object msg = m.get("message");
                return "操作失败：" + (msg == null ? "" : msg);
            }
            if (m.get("successCount") != null) {
                return "成功 " + m.get("successCount") + "，失败 " + m.get("failCount");
            }
            if (m.get("total") != null) {
                return "返回 " + m.get("total") + " 条记录";
            }
            if (m.get("maxBooks") != null) {
                return "最大 " + m.get("maxBooks") + "，已用 " + m.get("used") + "，剩余 " + m.get("remaining");
            }
            return "完成";
        }
        return "完成";
    }

    /**
     * M5：工具 → 前端渲染组件类型的映射。
     * <p>
     * 快照里带上 {@code type}，前端据此把 JSON 快照渲染成对应组件（图书卡、排行、图表…），
     * 而非让用户去读原始 JSON。未在此表中的工具不带 type，前端退化为默认的 JSON 展开视图——
     * 保证向后兼容，新工具不登记也不会出错。
     */
    private static final Map<String, String> SNAPSHOT_TYPES = Map.ofEntries(
            Map.entry("queryBooks", "book_grid"),
            Map.entry("queryHotBooks", "rank_list"),
            Map.entry("queryBorrowQuota", "quota_bar"),
            Map.entry("queryMyBorrows", "borrow_table"),
            Map.entry("queryMyFines", "fine_table"),
            Map.entry("queryCategories", "category_grid"),
            Map.entry("queryAnnouncements", "announce_list"),
            Map.entry("adminQueryBorrows", "borrow_table"),
            Map.entry("adminQueryFines", "fine_table"),
            Map.entry("adminQueryUsers", "user_table"),
            Map.entry("adminQueryStats", "stats_dashboard")
    );

    /**
     * 生成工具返回值的精简 JSON 快照。
     * <p>
     * 列表类结果（含 items）只保留前 {@link #LIST_SNAPSHOT_LIMIT} 条；
     * {@code queryBooks} 例外：保留全部条目（上限 {@link #BOOK_SEARCH_SNAPSHOT_LIMIT}），
     * 剔除 description 长文本，并按 {@link #BOOK_SEARCH_SNAPSHOT_MAX_LEN} 独立截断——
     * 前端用该快照驱动图书页面的 AI 检索结果视图，需要完整的图书列表而非前 3 条摘要，
     * 全局 600 字符上限会把 15 条结果截成非法 JSON 导致前端解析失败。
     * <p>
     * M5：附带 {@code type} 字段声明前端应渲染的组件类型。
     */
    private String snapshotData(Object r, String toolName) {
        if (r == null) return null;
        try {
            String genType = SNAPSHOT_TYPES.get(toolName);
            int maxLen = DATA_SNAPSHOT_MAX_LEN;
            Object toSerialize = r;
            // 生成式 UI 工具（带可视化 type）一律豁免全局 600 截断——
            // 截断会破坏 JSON，前端解析失败后卡片降级为折叠 JSON 视图，一级内联失效
            if (genType != null) {
                maxLen = "stats_dashboard".equals(genType) ? DASHBOARD_SNAPSHOT_MAX_LEN : GEN_UI_SNAPSHOT_MAX_LEN;
            }
            if (r instanceof Map<?, ?> m && m.get("items") instanceof List<?> list) {
                boolean fullList = "queryBooks".equals(toolName) || "queryHotBooks".equals(toolName);
                if (fullList) {
                    maxLen = BOOK_SEARCH_SNAPSHOT_MAX_LEN;
                }
                int cap = fullList ? BOOK_SEARCH_SNAPSHOT_LIMIT
                        : (genType != null ? TABLE_SNAPSHOT_LIMIT : LIST_SNAPSHOT_LIMIT);
                int limit = Math.min(list.size(), cap);
                List<Object> head = new ArrayList<>();
                for (int i = 0; i < limit; i++) {
                    if (fullList && list.get(i) instanceof Map<?, ?> item) {
                        Map<String, Object> slim = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : item.entrySet()) {
                            if (!"description".equals(e.getKey())) {
                                slim.put(String.valueOf(e.getKey()), e.getValue());
                            }
                        }
                        head.add(slim);
                    } else {
                        head.add(list.get(i));
                    }
                }
                Map<String, Object> slim = new LinkedHashMap<>();
                slim.put("total", m.get("total"));
                slim.put("items", head);
                slim.put("truncated", list.size() > limit);
                // 非列表型快照的顶层字段（如额度 maxBooks/used/remaining）也带过来，
                // 前端渲染进度等组件时需要
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!"items".equals(e.getKey()) && !"total".equals(e.getKey())) {
                        slim.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                toSerialize = slim;
            }
            // M5：声明前端渲染组件类型
            if (toSerialize instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> snapshot = (Map<String, Object>) map;
                String type = SNAPSHOT_TYPES.get(toolName);
                if (type != null) {
                    snapshot.put("type", type);
                }
            }
            String json = ctx.objectMapper.writeValueAsString(toSerialize);
            if (json.length() > maxLen) {
                return json.substring(0, maxLen) + "…";
            }
            return json;
        } catch (Exception e) {
            // 序列化失败不影响主流程
            return null;
        }
    }

    /** 构造授权卡的一行键值对（key/value） */
    protected static Map<String, String> row(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("key", key == null ? "" : key);
        m.put("value", value == null ? "" : value);
        return m;
    }

    // 通用辅助方法

    protected Map<String, Object> result(int total, List<Map<String, Object>> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("items", items);
        return m;
    }

    protected Integer parseStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) return null;
        try {
            return Integer.parseInt(status.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected String statusLabel(Integer s) {
        if (s == null) return "未知";
        return switch (s) {
            case 0 -> "待取书";
            case 1 -> "借阅中";
            case 2 -> "已归还";
            case 3 -> "已逾期";
            case 4 -> "已取消";
            case 5 -> "待确认归还";
            default -> "状态" + s;
        };
    }

    protected String fineStatusLabel(Integer s) {
        return s != null && s == 1 ? "已缴" : "未缴";
    }

    protected String userTypeLabel(Integer t) {
        if (t == null) return "未知";
        return switch (t) {
            case 1 -> "教师";
            case 2 -> "学生";
            case 3 -> "管理员";
            default -> "类型" + t;
        };
    }

    protected String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    protected String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    protected String str(Object o) {
        return o == null ? null : o.toString();
    }
}
