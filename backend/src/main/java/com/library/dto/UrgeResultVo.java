package com.library.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 催还结果（逐条含 success / message；skipped 为非逾期等被跳过的记录数）
 */
@Data
@AllArgsConstructor
public class UrgeResultVo {
    private List<Map<String, Object>> results;
    private int success;
    private int skipped;
}
