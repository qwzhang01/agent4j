package com.iwj.ancient.prose.dto.enums;


import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

/**
 * 权限关系
 *
 * @author avinzhang
 */
public enum RightLogicalEnum implements IEnum {

    /**
     * 且
     */
    AND("and"),

    /**
     * 或
     */
    OR("or"), NULL("");


    private final String code;

    RightLogicalEnum(String code) {
        this.code = code;
    }

    public static RightLogicalEnum getByCode(String code) {
        if (StringUtils.isEmpty(code)) {
            return null;
        }
        for (RightLogicalEnum e : RightLogicalEnum.values()) {
            if (Objects.equals(e.getCode(), code)) {
                return e;
            }
        }
        return NULL;
    }

    @Override
    public String getCode() {
        return null;
    }
}
