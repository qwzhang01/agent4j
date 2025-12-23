package com.iwj.ancient.prose.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

public class FileDto {
    @Data
    public static class OssPolicy {
        private String accessId;
        private String policy;
        private String signature;
        private String dir;
        private String host;
        private Integer expire;
        private String callback;
        private String stsToken;
    }

    @Data
    public static class Info {
        private String url;
        private String name;
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long size;
    }

    @Data
    public static class Base64 {
        @NotBlank
        private String base64;
        @NotBlank
        private String name;
        @NotNull
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long size;
    }
}
