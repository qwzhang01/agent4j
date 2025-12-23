package com.iwj.ancient.prose.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

public class LogMessageDto {
    @Data
    public static class List {
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long id;
        private String title;
        private String profile;
        private LocalDateTime createTime;
        private Boolean readFlag;
    }

    @Data
    public static class Unread {
        private int count;
        private java.util.List<List> list;
    }
}