package com.library.common.exception;

import com.library.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.stream.Collectors;

/**
 * 全局异常处理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Result.error(400, msg);
    }

    /**
     * SSE 响应已废（客户端断开/流已终止）时的专用收口：此时响应 Content-Type 已锁定
     * text/event-stream，常规 JSON Result 序列化必然失败（HttpMessageNotWritableException 级联 WARN）。
     * 返回 null 表示响应已处理完毕，只留低级别日志便于排查断连频率。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public Result<Void> handleSseUnusable(AsyncRequestNotUsableException e) {
        log.info("SSE 响应已失效（客户端断开或流已终止），跳过 JSON 错误响应：{}", e.getMessage());
        return null;
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        // 异常原文（含 SQL、mapper 路径、栈信息）一律不回传前端，避免信息泄露
        log.error("系统异常", e);
        return Result.error(500, "系统异常，请联系管理员");
    }
}
