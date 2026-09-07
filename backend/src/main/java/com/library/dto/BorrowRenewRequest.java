package com.library.dto;

import lombok.Data;

/**
 * 续借请求（支持 id 或 borrowNo）
 */
@Data
public class BorrowRenewRequest {
    private Long id;
    private String borrowNo;
}
