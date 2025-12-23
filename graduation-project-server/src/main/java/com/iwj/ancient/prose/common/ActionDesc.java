package com.iwj.ancient.prose.common;

import java.lang.annotation.*;


@Target({ElementType.TYPE, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ActionDesc {
    String desc() default "";
}