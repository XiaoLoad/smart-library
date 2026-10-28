package com.library.service;

import java.util.Set;

/**
 * AI 工具权限注册表：标记哪些工具属于「写操作」。
 * <p>
 * <b>为什么需要它</b>：写操作（借阅、续借、归还、柜台办理）会改变业务数据。
 * 早期版本仅靠系统 Prompt 的一条规则约束模型自觉先问再答，属软约束——
 * 模型偶尔会跳过确认直接调用。为把安全从「模型自觉」下沉到「代码强制」，
 * 所有写工具在此登记，由 {@link BaseLibraryTools} 在执行入口统一拦截，
 * 改为生成授权请求交给用户确认后才真正执行。
 * <p>
 * <b>安全策略</b>：登记制白名单——只有登记在 {@link #WRITE_TOOLS} 中的工具
 * 才走「授权卡确认」流程；未登记的工具名走写入口时会被直接拒绝执行
 * （fail-closed），避免新增写工具漏登记时被静默放行。
 * <p>
 * <b>新增写工具时</b>：在 {@link #WRITE_TOOLS} 中登记工具名，并在对应工具类
 * 中把授权卡标题与参数明细传入写入口（withWriteEvent）。
 * 只读工具不经过写入口，无需在此登记。
 */
public final class AiToolRegistry {

    /**
     * 需要用户授权的写工具集合。
     * <p>
     * 涵盖四个会改变业务数据的工具：读者侧借阅/续借/归还申请，管理员侧柜台办理。
     */
    public static final Set<String> WRITE_TOOLS = Set.of(
            "applyBorrow",
            "renewMyBorrow",
            "requestReturn",
            "adminCounterProcess",
            "adminCounterProcessBatch",
            "adminUrgeOverdue"
    );

    private AiToolRegistry() {
    }

    /**
     * 判断工具是否需要用户授权。
     *
     * @param toolName 工具名
     * @return true 表示需要确认后才执行；false 表示不是登记的写操作（写入口会拒绝执行）
     */
    public static boolean requiresConfirm(String toolName) {
        return toolName != null && WRITE_TOOLS.contains(toolName);
    }
}
