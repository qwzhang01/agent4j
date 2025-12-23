package com.iwj.ancient.prose.dto.enums;

/**
 * 内容状态
 *
 * @author avinzhang
 */
public enum ContentActionEnum implements IEnum {
    UNREAD("unRead"),
    READ("read"),
    THUMB_UP("thumbUp"),
    THUMB_DOWN("thumbDown");

    private final String code;

    ContentActionEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }
}