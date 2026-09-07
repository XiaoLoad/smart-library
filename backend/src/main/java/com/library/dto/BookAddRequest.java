package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class BookAddRequest {
    @NotBlank(message = "书名不能为空")
    private String title;
    @NotBlank(message = "作者不能为空")
    private String author;
    @NotBlank(message = "ISBN不能为空")
    private String isbn;
    private String publisher;
    private Long categoryId;
    private String location;
    private BigDecimal price;
    private String description;
    private Integer totalCount;
}
