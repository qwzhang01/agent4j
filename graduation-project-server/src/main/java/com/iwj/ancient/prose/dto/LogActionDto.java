package com.iwj.ancient.prose.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class LogActionDto {

    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class Query extends PageQuery {
        private String content;
    }

    @Data
    public static class List {
        private LocalDateTime actionTime;
        private BigDecimal timeConsuming;
        private String clientIp;
        private String module;
        private String url;
        private String account;
        private String userAgent;
        private String actionDesc;
    }
}
