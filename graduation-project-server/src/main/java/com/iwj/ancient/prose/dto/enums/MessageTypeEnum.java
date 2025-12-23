package com.iwj.ancient.prose.dto.enums;

import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

/**
 * 站内信业务
 *
 * @author avinzhang
 */
public enum MessageTypeEnum implements IEnum {
    /**
     * 通知
     */
    NOTIFY("notify"),
    /**
     * 空
     */
    NULL("");

    private final String code;

    MessageTypeEnum(String code) {
        this.code = code;
    }

    public static MessageTypeEnum getByCode(String code) {
        if (StringUtils.isEmpty(code)) {
            return null;
        }
        for (MessageTypeEnum e : MessageTypeEnum.values()) {
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
