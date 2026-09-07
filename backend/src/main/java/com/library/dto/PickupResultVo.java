package com.library.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 批量取书结果
 */
@Data
@AllArgsConstructor
public class PickupResultVo {
    private List<Map<String, Object>> results;
    private int successCount;
    private int failCount;
}
