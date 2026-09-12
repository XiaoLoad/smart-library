package com.library.dto;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

import java.util.List;

/**
 * 催还请求（单条或批量，按借阅码向逾期记录下发催还通知）
 */
@Data
public class BorrowUrgeRequest {

    /** 借阅码列表（列表页勾选或行内催还） */
    private List<String> borrowNos;

    @AssertTrue(message = "请提供要催还的借阅记录")
    public boolean hasTargets() {
        return borrowNos != null && !borrowNos.isEmpty();
    }
}
