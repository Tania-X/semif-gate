package com.semif.gate.registry;

/**
 * 注册表加载或校验失败。
 *
 * <p>这个异常在启动阶段抛出，语义是<b>拒绝启动</b>：
 * 判定点契约有问题时，系统宁可不启动，也不要带着错误的契约提供服务。
 * 原因很简单——契约错误造成的后果是「静默返回错误语义的判定」，
 * 这种故障在线上极难发现，而启动失败一眼就能看到。
 */
public class RegistryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RegistryException(String message) {
        super(message);
    }

    public RegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
