package com.iwj.ancient.prose.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 邮件
 *
 * @author avinzhang
 */
@Data
@AllArgsConstructor
public class MailDto {
    private String title;
    private String to;
    private String content;
    private String base64File;
}