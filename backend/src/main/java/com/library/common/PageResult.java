package com.library.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

/**
 * 分页结果
 */
@Data
@AllArgsConstructor
public class PageResult<T> {
    private List<T> list;
    private long total;
}
