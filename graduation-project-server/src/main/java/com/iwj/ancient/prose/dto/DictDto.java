package com.iwj.ancient.prose.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public class DictDto {
    @ApiModel("字典下拉")
    @Data
    public static class Select {
        private String type;
        private String key;
        private String value;
    }

    @ApiModel("时间区间")
    @Data
    public static class SectionTime {
        private LocalDate from;
        private LocalDate to;
    }

    @ApiModel("整数映射")
    @Data
    public static class MapInt {
        @NotNull(message = "真实值不能为空")
        private int real;
        @NotNull(message = "使用值不能为空")
        private int as;
    }

    @ApiModel("营销配置")
    @Data
    public static class MkConfig {
        private String type;
        private String value;
        private List<MapInt> complexValue;
    }

    @ApiModel("整数映射")
    @Data
    public static class WxMa {
        @ApiModelProperty("微信小程序的appid")
        private String appid;
        @ApiModelProperty("微信小程序的Secret")
        private String secret;
        @ApiModelProperty("微信小程序消息服务器配置的token")
        private String token;
        @ApiModelProperty("微信小程序消息服务器配置的EncodingAESKey")
        private String aesKey;
        @ApiModelProperty("JSON")
        private String msgDataFormat;
    }
}