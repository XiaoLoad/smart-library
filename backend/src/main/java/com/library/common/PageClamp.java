package com.library.common;

/**
 * 分页参数钳制工具。
 * <p>
 * 所有接受 page/size 查询参数的列表接口统一走这里：
 * 构造超大 size（如 size=100000）会一次拉全表进内存，拖垮 DB 与 JVM；
 * 负数 page 在 PageHelper 下语义异常。钳制为 page ≥ 1、size ∈ [1, 100]。
 */
public final class PageClamp {

    /** 单页最大条数 */
    public static final int MAX_SIZE = 100;

    private PageClamp() {
    }

    /** page：小于 1 归一为 1 */
    public static int page(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    /** size：空/小于 1 归一为 10，超过 100 钳到 100 */
    public static int size(Integer size) {
        if (size == null || size < 1) {
            return 10;
        }
        return Math.min(size, MAX_SIZE);
    }
}
