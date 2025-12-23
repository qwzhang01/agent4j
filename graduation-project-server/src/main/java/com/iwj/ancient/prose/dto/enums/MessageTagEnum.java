package com.iwj.ancient.prose.dto.enums;

import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

/**
 * 站内信类型
 *
 * @author avinzhang
 */
public enum MessageTagEnum implements IEnum {

    /**
     * 预警消息
     */
    WARNING("warning"),
    /**
     * 知会消息
     */
    NOTIFY("notify"),
    /**
     * 空
     */
    NULL("");

    private final String code;

    MessageTagEnum(String code) {
        this.code = code;
    }

    public static MessageTagEnum getByCode(String code) {
        if (StringUtils.isEmpty(code)) {
            return null;
        }
        for (MessageTagEnum e : MessageTagEnum.values()) {
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
