package com.werewolf.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 全局异常处理：所有未捕获异常统一返回 JSON，不向客户端泄漏堆栈。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> badBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求格式错误"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "参数校验失败"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "上传文件过大"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> illegalArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "参数非法" : e.getMessage()));
    }

    /** 缺失静态资源（如 /favicon.ico）按 404 静默处理，不再刷 ERROR 日志。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "资源不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> generic(Exception e, HttpServletRequest request) {
        // 补充请求上下文（方法 + URI），便于定位问题；堆栈仅进日志，不返回给客户端。
        String method = request.getMethod();
        String uri = request.getRequestURI();
        log.error("未处理异常 {} {} - {}", method, uri, e.toString(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "服务器内部错误"));
    }
}
