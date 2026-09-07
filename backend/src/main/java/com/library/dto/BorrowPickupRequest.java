package com.library.dto;

import lombok.Data;
import java.util.List;

/**
 * 取书请求（支持单个或批量）
 */
@Data
public class BorrowPickupRequest {
    private String borrowNo;
    private List<String> borrowNos;
}
