package com.library.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 批量办理结果（逐条含 action：pickup 取书 / return 归还 / confirm 归还确认）
 */
@Data
@AllArgsConstructor
public class PickupResultVo {
    private List<Map<String, Object>> results;
    private int successCount;
    private int failCount;
}
