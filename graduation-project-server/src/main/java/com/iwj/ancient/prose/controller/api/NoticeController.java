package com.iwj.ancient.prose.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iwj.ancient.prose.common.ActionDesc;
import com.iwj.ancient.prose.common.UserContext;
import com.iwj.ancient.prose.dto.LogMessageDto;
import com.iwj.ancient.prose.dto.PageQuery;
import com.iwj.ancient.prose.entity.LogMessage;
import com.iwj.ancient.prose.service.LogMessageService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("api/notice")
@Slf4j
@AllArgsConstructor
@ActionDesc(desc = "API 消息")
public class NoticeController {
    private final LogMessageService messageService;

    @PostMapping(value = "list/{type}")
    public Page<LogMessageDto.List> list(@PathVariable("type") String type, @RequestBody PageQuery query) {
        return messageService.list(new Page<>(query.getCurrent(), query.getSize()), type);
    }

    @PostMapping(value = "unread")
    public LogMessageDto.Unread unread() {
        LambdaQueryWrapper<LogMessage> query = Wrappers.lambdaQuery(LogMessage.class);
        query.eq(LogMessage::getUserId, UserContext.getId());
        query.eq(LogMessage::getReadFlag, false);
        query.orderByDesc(LogMessage::getCreateTime);

        int count = messageService.count(query);

        Page<LogMessage> page = messageService.page(new Page<>(1, 4), query);

        LogMessageDto.Unread unread = new LogMessageDto.Unread();
        unread.setCount(count);
        if (page.getRecords() != null && !page.getRecords().isEmpty()) {
            unread.setList(page.getRecords().stream().map(r -> {
                LogMessageDto.List list = new LogMessageDto.List();
                BeanUtils.copyProperties(r, list);
                return list;
            }).collect(Collectors.toList()));
        }

        return unread;
    }

    @PutMapping(value = "read/{id}")
    public void read(@PathVariable("id") Long id) {
        messageService.read(Collections.singletonList(id), UserContext.getId());
    }

    @PutMapping(value = "read-all")
    public void readAll() {
        LambdaQueryWrapper<LogMessage> query = Wrappers.lambdaQuery(LogMessage.class);
        query.eq(LogMessage::getUserId, UserContext.getId());
        query.eq(LogMessage::getReadFlag, false);
        query.select(LogMessage::getId);
        List<Long> ids = messageService.listObjs(query, v -> Long.parseLong(String.valueOf(v)));
        messageService.read(ids, UserContext.getId());
    }

    @DeleteMapping(value = "{id}")
    @ActionDesc(desc = "删除站内信")
    public void del(@PathVariable("id") Long id) {
        messageService.removeById(id);
    }
}
