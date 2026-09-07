package com.library.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量归还请求
 */
@Data
public class BatchReturnRequest {
    @NotEmpty(message = "请选择要归还的借阅记录")
    private List<Long> ids;
}
