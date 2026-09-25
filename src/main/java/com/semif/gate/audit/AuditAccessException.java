package com.semif.gate.audit;

/**
 * 审计写入失败。
 *
 * <p>与缓存失败不同，这个异常<b>必须向上传播</b>：审计记录是「这条判定怎么来的」
 * 的唯一证据，丢了它，系统表面上照常工作，但任何一次事故复盘都无从下手。
 */
public class AuditAccessException extends RuntimeException {

    public AuditAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
