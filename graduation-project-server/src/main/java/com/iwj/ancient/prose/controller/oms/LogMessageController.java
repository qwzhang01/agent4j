package com.iwj.ancient.prose.controller.oms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iwj.ancient.prose.common.ActionDesc;
import com.iwj.ancient.prose.dto.PageQuery;
import com.iwj.ancient.prose.dto.enums.MessageTagEnum;
import com.iwj.ancient.prose.dto.enums.MessageTypeEnum;
import com.iwj.ancient.prose.entity.LogMessage;
import com.iwj.ancient.prose.exception.ParamException;
import com.iwj.ancient.prose.service.LogMessageService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/oms/log/message")
@Slf4j
@AllArgsConstructor
@ActionDesc(desc = "OMS 站内信")
public class LogMessageController {

    private final LogMessageService messageService;

    @PostMapping(value = "list")
    public Page<LogMessage> list(@RequestBody PageQuery query) {
        LambdaQueryWrapper<LogMessage> lqw = Wrappers.lambdaQuery(LogMessage.class);
        if (com.baomidou.mybatisplus.core.toolkit.StringUtils.isNotBlank(query.getName())) {
            lqw.like(LogMessage::getTitle, query.getName());
        }
        lqw.orderByDesc(LogMessage::getId);
        return messageService.page(new Page<>(query.getCurrent(), query.getSize()), lqw);
    }

    @PostMapping(value = "add")
    public Boolean add(@RequestBody LogMessage message) {
        if (StringUtils.isBlank(message.getProfile()) || StringUtils.isBlank(message.getTitle())) {
            throw new ParamException("标题和内容不能为空");
        }
        return messageService.add(MessageTagEnum.NOTIFY, MessageTypeEnum.NOTIFY, 0L, 0L, message.getTitle(), message.getProfile());
    }
}