package com.semif.gate.cache;

/**
 * 缓存访问失败。
 *
 * <p>刻意与「未命中」区分开：调用方可以决定「缓存坏了就当作没命中，继续调 provider」，
 * 但那必须是一个<b>显式决定</b>，而不是因为异常被吞掉而发生的既成事实。
 */
public class CacheAccessException extends RuntimeException {

    public CacheAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
