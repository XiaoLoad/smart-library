package com.library.service;

/**
 * 写操作权限档位（M7）。
 * <p>
 * 参考 ZCode 的执行模式设计：权限不是二元的「问/不问」，而是按任务风险分档，
 * 由用户在界面上按场景切换。三个档位：
 * <ul>
 *   <li>{@link #CONFIRM} 逐笔确认（默认）：每个写工具执行前弹授权卡，用户逐笔点头；</li>
 *   <li>{@link #BATCH} 批量授权：本轮各写操作累积，本轮结束前合并成一张批量授权卡，
 *       用户一次确认整批执行——适合「把待取书的都办了」这类多步柜台任务；</li>
 *   <li>{@link #AUTO} 自动执行：写工具直接执行，回执卡片与轨迹照常记录；
 *       仅管理员可切换，读者端始终逐笔确认。</li>
 * </ul>
 * <p>
 * 档位挂在会话（ai_conversations.permission_mode）上而非用户全局：
 * 演示/日常/信任三种场景按会话切换互不干扰，也便于回滚审计。
 */
public enum PermissionMode {

    /** 逐笔确认（默认） */
    CONFIRM("confirm"),
    /** 批量授权：本轮写操作累积，统一确认 */
    BATCH("batch"),
    /** 自动执行（仅管理员） */
    AUTO("auto");

    private final String code;

    PermissionMode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 按存储编码解析；空值/未知值回退默认逐笔确认（fail-safe：新档位上线前旧数据全按确认走） */
    public static PermissionMode of(String code) {
        if (code == null || code.isBlank()) {
            return CONFIRM;
        }
        for (PermissionMode m : values()) {
            if (m.code.equalsIgnoreCase(code.trim())) {
                return m;
            }
        }
        return CONFIRM;
    }
}
