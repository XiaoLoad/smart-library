package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * RAG 文档「粘贴文本」录入请求。
 */
@Data
public class RagTextRequest {
    /** 文档标题 */
    @NotBlank(message = "文档标题不能为空")
    private String title;
    /** 可见范围：public=读者可见（默认），admin=仅管理员 */
    private String scope;
    /** 正文 */
    @NotBlank(message = "文档内容不能为空")
    private String content;
}