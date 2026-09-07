package com.library.dto;

import lombok.Data;

/**
 * 归还请求（支持 id 或 borrowNo）
 */
@Data
public class BorrowReturnRequest {
    private Long id;
    private String borrowNo;
}
