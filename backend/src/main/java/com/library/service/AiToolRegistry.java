package com.library.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 工具权限注册表：标记哪些工具属于「写操作」，并为它们提供人类可读的授权摘要。
 * <p>
 * <b>为什么需要它</b>：写操作（借阅、续借、归还、柜台办理）会改变业务数据。
 * 早期版本仅靠系统 Prompt 的一条规则约束模型自觉先问再答，属软约束——
 * 模型偶尔会跳过确认直接调用。为把安全从「模型自觉」下沉到「代码强制」，
 * 所有写工具在此登记，由 {@link BaseLibraryTools} 在执行入口统一拦截，
 * 改为生成授权请求交给用户确认后才真正执行。
 * <p>
 * <b>新增写工具时必须做两件事</b>（否则会被漏拦）：
 * <ol>
 *   <li>在 {@link #WRITE_TOOLS} 中登记工具名；</li>
 *   <li>在对应工具类中实现 {@code describeAction}，提供授权卡上展示的摘要。</li>
 * </ol>
 * 只读工具默认直接执行（{@link #ALLOW}），无需登记。
 */
public final class AiToolRegistry {

    /** 工具权限：允许直接执行 */
    public static final String ALLOW = "ALLOW";
    /** 工具权限：需要用户确认后才执行 */
    public static final String ASK = "ASK";

    /**
     * 需要用户授权的写工具集合。
     * <p>
     * 涵盖四个会改变业务数据的工具：读者侧借阅/续借/归还申请，管理员侧柜台办理。
     */
    public static final Set<String> WRITE_TOOLS = Set.of(
            "applyBorrow",
            "renewMyBorrow",
            "requestReturn",
            "adminCounterProcess"
    );

    /**
     * 写操作的授权摘要构造器：由各工具类实现，产出授权卡上展示的内容。
     */
    @FunctionalInterface
    public interface ActionDescriber {
        /**
         * 生成授权摘要。
         *
         * @param title 操作标题（如「借阅《围城》」）
         * @param rows  参数键值表，供前端渲染成明细列表
         */
        Summary describe(String title, List<Map<String, String>> rows);
    }

    /**
     * 授权摘要：标题 + 参数明细。
     *
     * @param title 操作标题
     * @param rows 参数明细（可为空；前端在无明细时不渲染该区域）
     */
    public record Summary(String title, List<Map<String, String>> rows) {
        public Summary {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    private AiToolRegistry() {
    }

    /**
     * 判断工具是否需要用户授权。
     *
     * @param toolName 工具名
     * @return true 表示需要确认后才执行
     */
    public static boolean requiresConfirm(String toolName) {
        return toolName != null && WRITE_TOOLS.contains(toolName);
    }
}
