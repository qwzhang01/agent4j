package com.iwj.ancient.prose.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 用户
 *
 * @author avinzhang
 */
@Data
public class ClientDto {
    @Data
    public static class Register {
        @NotBlank(message = "用户名不能为空")
        private String account;
        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @Data
    public static class Login {
        @NotBlank(message = "用户名不能为空")
        private String account;
        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @Data
    public static class Edit {
        @NotBlank
        private String name;
        @NotBlank
        private String email;
        @NotBlank
        private String phone;
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class Info extends Edit {
        private Long id;
        private String account;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }
}