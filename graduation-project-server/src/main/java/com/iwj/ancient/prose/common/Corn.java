package com.iwj.ancient.prose.common;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * 定时任务注解
 *
 * @author avinzhang
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Corn {
    String value();
}
