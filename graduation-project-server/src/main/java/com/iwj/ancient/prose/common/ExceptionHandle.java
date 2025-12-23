package com.iwj.ancient.prose.common;

import com.iwj.ancient.prose.exception.AncientException;
import com.iwj.ancient.prose.exception.ParamException;
import com.iwj.ancient.prose.kit.JsonKit;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * @author avinzhang
 */
@RestControllerAdvice
@Slf4j
@AllArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExceptionHandle {

    /**
     * 全局自定义异常处理器
     *
     * @param e
     * @param request
     * @param response
     * @return
     */
    @ExceptionHandler({AncientException.class})
    @ResponseStatus(HttpStatus.OK)
    public R<?> ancientException(AncientException e, HttpServletRequest request, HttpServletResponse response) {
        log.error("全局自定义异常", e);
        return R.error(e.getLocalizedMessage());
    }

    /**
     * 全局入参异常
     *
     * @param e
     * @param request
     * @param response
     * @return
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class, BindException.class, MethodArgumentNotValidException.class
            , ConstraintViolationException.class, ParamException.class})
    @ResponseStatus(HttpStatus.OK)
    public R<?> requestParamException(Exception e, HttpServletRequest request, HttpServletResponse response) {
        if (e instanceof ParamException) {
            return R.warn(e.getLocalizedMessage());
        }
        if (e instanceof MethodArgumentTypeMismatchException) {
            MethodArgumentTypeMismatchException me = (MethodArgumentTypeMismatchException) e;
            Map<String, Object> param = new HashMap<>();
            param.put(me.getName(), me.getValue());
            return R.error(JsonKit.obj2String(param));
        }
        if (e instanceof MissingServletRequestParameterException) {
            return R.error(e.getLocalizedMessage());
        }
        if (e instanceof BindException) {
            BindException be = (BindException) e;
            BindingResult bindingResult = be.getBindingResult();
            List<FieldError> errors = bindingResult.getFieldErrors();
            String errorMsg = errors.stream().map(DefaultMessageSourceResolvable::getDefaultMessage).collect(Collectors.joining(","));
            return R.error(errorMsg);
        }
        if (e instanceof MethodArgumentNotValidException) {
            MethodArgumentNotValidException me = (MethodArgumentNotValidException) e;
            BindingResult bindingResult = me.getBindingResult();
            List<ObjectError> errors = bindingResult.getAllErrors();
            String errorMsg = errors.stream().map(DefaultMessageSourceResolvable::getDefaultMessage).collect(Collectors.joining(","));
            return R.error(errorMsg);
        }
        return R.error(e);
    }

    /**
     * 全局异常处理器
     *
     * @param e
     * @param request
     * @param response
     * @return
     */
    @ExceptionHandler({Exception.class})
    @ResponseStatus(HttpStatus.OK)
    public R<?> exception(Exception e, HttpServletRequest request, HttpServletResponse response) {
        log.error("全局未知异常：", e);
        return R.error(e.getLocalizedMessage());
    }
}