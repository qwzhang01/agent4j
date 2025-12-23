package com.iwj.ancient.prose.exception;

/**
 * 异常基类
 *
 * @author avinzhang
 */
public class AncientException extends RuntimeException {
    public AncientException() {
        super();
    }

    public AncientException(String msg) {
        super(msg);
    }
    public AncientException(Exception msg) {
        super(msg);
    }
    public AncientException(String msg, Exception exception) {
        super(msg, exception);
    }
}