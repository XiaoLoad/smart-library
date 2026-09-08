package com.library.service;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek 思考模式兼容 HTTP 客户端装饰器。
 * <p>
 * 背景：DeepSeek V3.1+ / V4 系列模型默认开启 thinking 模式，响应中的思维链通过
 * 与 content 同级的 {@code reasoning_content} 字段返回。官方协议规定：
 * <b>携带 tools 参数的请求，后续所有轮次必须把 reasoning_content 原样回传</b>，
 * 否则返回 400：{@code The reasoning_content in the thinking mode must be passed back}。
 * <p>
 * LangChain4j 1.0.0 的 OpenAI 集成既不解析也不支持回传 reasoning_content
 *（AiMessage 只有 text/toolExecutionRequests），而本系统的对话始终携带 tools，
 * 因此在工具调用 / 多轮对话时必然触发 400。
 * <p>
 * 方案（参考 DeepSeek 官方文档思考模式开关）：在请求体注入
 * {@code "thinking":{"type":"disabled"}} 显式关闭思考模式。
 * 本助手用于馆藏数据查询，无需深度推理；关闭后响应更快、费用更低，
 * 也规避了 reasoning_content 回传问题。
 * <p>
 * 实现方式：装饰 langchain4j-http-client 的 HttpClient（与官方 LoggingHttpClient 同模式），
 * 对 DeepSeek 模型的请求体做字符串级注入，不侵入 LangChain4j 内部结构。
 */
public class DeepSeekCompatHttpClient implements HttpClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekCompatHttpClient.class);

    /** 从请求体提取 model 字段值 */
    private static final Pattern MODEL_PATTERN = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient delegate;

    public DeepSeekCompatHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        return delegate.execute(patch(request));
    }

    @Override
    public void execute(HttpRequest request,
                        ServerSentEventParser parser,
                        ServerSentEventListener listener) {
        delegate.execute(patch(request), parser, listener);
    }

    /** 按需改写请求 */
    private HttpRequest patch(HttpRequest request) {
        String body = request.body();
        String patched = injectThinkingDisabled(body);
        if (patched == body) {
            return request;
        }
        log.debug("[DeepSeekCompat] 已在请求体注入 thinking:disabled");
        return HttpRequest.builder()
                .method(request.method())
                .url(request.url())
                .headers(request.headers())
                .body(patched)
                .build();
    }

    /**
     * 对 DeepSeek 系列模型在 JSON 请求体顶层注入 {@code "thinking":{"type":"disabled"}}。
     * 已存在 thinking 字段或非 DeepSeek 模型时原样返回。
     */
    static String injectThinkingDisabled(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        if (body.contains("\"thinking\"")) {
            return body; // 已显式配置，不重复注入
        }
        Matcher m = MODEL_PATTERN.matcher(body);
        if (!m.find()) {
            return body;
        }
        String model = m.group(1).toLowerCase();
        if (!model.contains("deepseek")) {
            return body; // 仅对 DeepSeek 模型生效，其他厂商请求体不加未知字段
        }
        int idx = body.indexOf('{');
        if (idx < 0) {
            return body;
        }
        return body.substring(0, idx + 1)
                + "\"thinking\":{\"type\":\"disabled\"},"
                + body.substring(idx + 1);
    }

    /**
     * 配套 Builder：内部构建 JdkHttpClient 并用兼容装饰器包装，
     * 超时配置透传给底层 JDK builder（LangChain4j 的 timeout() 会作用到此 builder）。
     */
    public static class Builder implements HttpClientBuilder {
        private final JdkHttpClientBuilder inner = new JdkHttpClientBuilder();

        @Override
        public HttpClient build() {
            return new DeepSeekCompatHttpClient(inner.build());
        }

        @Override
        public Duration connectTimeout() {
            return inner.connectTimeout();
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            inner.connectTimeout(timeout);
            return this;
        }

        @Override
        public Duration readTimeout() {
            return inner.readTimeout();
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            inner.readTimeout(timeout);
            return this;
        }
    }
}
