package com.library.dto;

import lombok.Data;

/**
 * AI 渠道连通性测试请求。
 * <p>
 * 两种用法：
 * <ul>
 *   <li>传 id：测试已保存渠道（apiKey 用库中存储的，无需回显）</li>
 *   <li>传 provider/apiKey/endpoint：测试表单中尚未保存的配置</li>
 * </ul>
 */
@Data
public class AiChannelTestRequest {
    /** 已保存渠道 ID（测试已保存渠道时传） */
    private Long id;
    private String provider;
    private String apiKey;
    private String endpoint;
}
