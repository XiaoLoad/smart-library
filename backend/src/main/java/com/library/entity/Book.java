package com.library.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 图书实体
 */
@Data
public class Book {
    private Long id;
    private String title;
    private String author;
    private String publisher;
    private String isbn;
    private Long categoryId;
    private String location;
    private BigDecimal price;
    private LocalDate publishDate;
    private String coverUrl;
    private String description;
    private Integer totalCount;
    private Integer availableCount;
    private Integer status;
    private LocalDateTime createTime;
}
