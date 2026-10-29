package com.example.ragassistant.web;

import org.springframework.http.HttpStatus;

/** 可以安全展示给调用者的业务错误，不携带底层 SQL、路径或模型响应。 */
public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
    public HttpStatus status() { return status; }
    public static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "资源不存在或无访问权限");
    }
}
