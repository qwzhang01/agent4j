package com.iwj.ancient.prose.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;
import java.util.List;

public class AccountDto {
    @Data
    public static class Request implements Serializable {
        @NotBlank(message = "用户名不能为空")
        private String account;
        @NotBlank(message = "密码不能为空")
        private String password;
    }
    @Data
    public static class Context implements Serializable {
        private String token;
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long id;
        private String ip;
    }

    @Data
    public static class Info implements Serializable {

        @ApiModelProperty("账户主键id")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long id;

        @ApiModelProperty("账户编码")
        private String account;

        @ApiModelProperty("账户名称")
        private String name;

        @ApiModelProperty("角色名称")
        private List<String> roles;
        @ApiModelProperty("权限项编码")
        private List<String> rightItemCodes;
    }
}