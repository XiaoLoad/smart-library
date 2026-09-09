package com.library.dto;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

import java.util.List;

/**
 * 批量办理请求（取书 / 归还 / 归还确认统一入口，按记录状态自动分流）
 */
@Data
public class BatchProcessRequest {

    /** 借阅记录 ID 列表（列表页勾选办理） */
    private List<Long> ids;

    /** 借阅码列表（扫码办理） */
    private List<String> borrowNos;

    @AssertTrue(message = "请提供要办理的借阅记录")
    public boolean hasTargets() {
        return (ids != null && !ids.isEmpty()) || (borrowNos != null && !borrowNos.isEmpty());
    }
}
