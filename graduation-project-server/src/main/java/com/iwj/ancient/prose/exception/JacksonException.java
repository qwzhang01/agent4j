package com.iwj.ancient.prose.exception;

/**
 * json 序列化 反序列化异常
 *
 * @author avinzhang
 */
public class JacksonException extends AncientException {

    private String json;
    private Class<?> clazz;

    public JacksonException(String errorDesc, String json, Class<?> clazz) {
        super(errorDesc);
        this.json = json;
        this.clazz = clazz;
    }

    public JacksonException() {
        super();
    }

    public JacksonException(String msg) {
        super(msg);
    }

    public JacksonException(String msg, Exception exception) {
        super(msg, exception);
    }
}