package com.iwj.ancient.prose.common;

import com.iwj.ancient.prose.dto.enums.RightLogicalEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * 功能权限拦截器注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auth {
    /**
     * 权限项编码数组
     *
     * @return
     */
    String[] permission();

    /**
     * 权限编码数组的关系
     * 或,任意一个符合即有权限
     * 且,所有符合才有权限
     *
     * @return
     */
    RightLogicalEnum logical() default RightLogicalEnum.OR;
}