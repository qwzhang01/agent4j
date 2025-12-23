package com.iwj.ancient.prose.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.http.HttpStatus;

import java.io.Serializable;

/**
 * 返回结果
 *
 * @param <T>
 * @author alvin
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;
    /**
     * 成功
     */
    public static Integer SUCCESS = HttpStatus.OK.value();
    /**
     * 未登录
     */
    public static Integer UN_LOGIN = 300;
    /**
     * 没有权限
     */
    public static Integer UN_AUTH = 400;
    public static Integer WARN = 600;
    /**
     * 系统错误
     */
    public static Integer ERROR = HttpStatus.INTERNAL_SERVER_ERROR.value();
    private Boolean success;
    private String msg;
    private String token;
    private T data;
    private Integer code;

    public static <T> R<T> ok() {
        R<T> r = new R<T>();
        r.setCode(SUCCESS);
        r.setSuccess(true);
        r.setMsg("成功");
        r.setToken(UserContext.getToken());
        return r;
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<T>();
        r.setCode(SUCCESS);
        r.setSuccess(true);
        r.setMsg("成功");
        r.setData(data);
        r.setToken(UserContext.getToken());
        return r;
    }

    public static R<?> error(String msg) {
        R<String> r = new R<>();
        r.setCode(ERROR);
        r.setSuccess(false);
        r.setMsg(StringUtils.isNotBlank(msg) ? msg : "服务器异常");
        r.setData(null);
        r.setToken(UserContext.getToken());
        return r;
    }

    public static R<?> error(Exception e) {
        R<String> r = new R();
        r.setCode(ERROR);
        r.setSuccess(false);
        r.setMsg("失败");
        r.setData(ExceptionUtils.getStackTrace(e));
        r.setToken(UserContext.getToken());
        return r;
    }

    public static R<?> error(String msg, Exception e) {
        R<String> r = new R<>();
        r.setCode(ERROR);
        r.setSuccess(false);
        r.setMsg(msg);
        r.setData(null);
        r.setToken(UserContext.getToken());
        r.setData(ExceptionUtils.getStackTrace(e));
        return r;
    }

    public static R<?> unLogin() {
        R<String> r = new R();
        r.setCode(UN_LOGIN);
        r.setSuccess(false);
        r.setMsg("登录失效");
        r.setData(null);
        r.setToken(null);
        return r;
    }

    public static R<?> unAuth() {
        R<String> r = new R<>();
        r.setCode(UN_AUTH);
        r.setSuccess(false);
        r.setMsg("没有权限");
        r.setData(null);
        r.setToken(UserContext.getToken());
        return r;
    }

    public static R<?> warn(String msg) {
        R<String> r = new R<>();
        r.setCode(WARN);
        r.setSuccess(false);
        r.setMsg(StringUtils.isBlank(msg) ? "参数错误" : msg);
        r.setData(null);
        r.setToken(UserContext.getToken());
        return r;
    }
}
