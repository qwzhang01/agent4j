package com.iwj.ancient.prose.common;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 日志切面
 *
 * @author avinzhang
 */
@Component
@Aspect
@Slf4j
public class LogAspect {

    /**
     * mapper 切点
     */
    @Pointcut("execution(* com.iwj.ancient.prose.mapper.*.*(..)))")
    public void mapperParamLog() {
    }

    /**
     * mapper 入参打印 执行时长打印
     *
     * @param pjp
     */
    @Around(value = "mapperParamLog()")
    public Object mapperAround(ProceedingJoinPoint pjp) throws Throwable {
        printArgs(pjp, "Mapper");
        return duration(pjp, "Mapper");
    }

    /**
     * service 切点
     */
    @Pointcut("execution(* com.iwj.ancient.prose.service.impl.*.*(..)))")
    public void serviceParamLog() {
    }

    /**
     * service 入参打印 执行时长打印
     *
     * @param pjp
     */
    @Around(value = "serviceParamLog()")
    public Object serverAround(ProceedingJoinPoint pjp) throws Throwable {
        printArgs(pjp, "ServiceImpl");
        return duration(pjp, "ServiceImpl");
    }

    /**
     * 打印执行时长
     *
     * @param pjp
     * @param pack
     * @return
     * @throws Throwable
     */
    private Object duration(ProceedingJoinPoint pjp, String pack) throws Throwable {
        LocalDateTime start = LocalDateTime.now();
        Object proceed = pjp.proceed();
        LocalDateTime end = LocalDateTime.now();
        Duration duration = Duration.between(start, end);
        log.info(pack + " 执行日志，class:{}，method:{}，执行时长毫秒[{}]", pjp.getTarget().getClass().getName(), pjp.getSignature().getName(), duration.toMillis());
        return proceed;
    }

    /**
     * 打印入参
     *
     * @param jp
     * @param pack
     */
    private void printArgs(ProceedingJoinPoint jp, String pack) {
        try {
            log.info(pack + " 执行日志，class:{}，method:{}，参数[{}]", jp.getTarget().getClass().getName(), jp.getSignature().getName(), Arrays.toString(jp.getArgs()));
        } catch (Exception e) {
            log.warn(pack + " 方法参数打印日志异常", e);
        }
    }
}