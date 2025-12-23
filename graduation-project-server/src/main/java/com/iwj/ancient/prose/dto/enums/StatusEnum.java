package com.iwj.ancient.prose.dto.enums;

import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

/**
 * 状态 启动中/禁用中
 *
 * @author avinzhang
 */
public enum StatusEnum implements IEnum {

    /**
     * 启动中	正常状态
     */
    ENABLE("enable"),

    /**
     * 禁用中	人为或过期导致不可用
     */
    DISABLE("disable"),

    /**
     * 空
     */
    NULL("");

    private final String code;

    StatusEnum(String code) {
        this.code = code;
    }

    public static StatusEnum getByCode(String code) {
        if (StringUtils.isEmpty(code)) {
            return null;
        }
        for (StatusEnum e : StatusEnum.values()) {
            if (Objects.equals(e.getCode(), code)) {
                return e;
            }
        }
        return NULL;
    }

    @Override
    public String getCode() {
        return code;
    }
}
