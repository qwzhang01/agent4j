package com.iwj.ancient.prose.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iwj.ancient.prose.common.UserContext;
import com.iwj.ancient.prose.dto.LogMessageDto;
import com.iwj.ancient.prose.dto.enums.ContentActionEnum;
import com.iwj.ancient.prose.dto.enums.MessageTagEnum;
import com.iwj.ancient.prose.dto.enums.MessageTypeEnum;
import com.iwj.ancient.prose.entity.Client;
import com.iwj.ancient.prose.entity.LogMessage;
import com.iwj.ancient.prose.entity.LogMessageAction;
import com.iwj.ancient.prose.mapper.LogMessageMapper;
import com.iwj.ancient.prose.service.ClientService;
import com.iwj.ancient.prose.service.LogMessageActionService;
import com.iwj.ancient.prose.service.LogMessageService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 操作记录 服务实现类
 * </p>
 *
 * @author avinzhang
 * @since 2024-01-29
 */
@Service
@Slf4j
@AllArgsConstructor
public class LogMessageServiceImpl extends ServiceImpl<LogMessageMapper, LogMessage> implements LogMessageService {
    private final ClientService clientService;
    private final LogMessageActionService messageActionService;

    @Override
    public Page<LogMessageDto.List> list(Page<LogMessage> page, String type) {
        Long userId = UserContext.getId();
        return baseMapper.list(page, type, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean add(MessageTagEnum tag, MessageTypeEnum type, Long targetId, Long userId, String title, String profile) {
        LogMessage warning = new LogMessage();
        warning.setTagType(tag.getCode());
        warning.setType(type.getCode());
        warning.setTitle(title);
        warning.setProfile(profile);
        warning.setTargetId(targetId);
        warning.setUserId(userId);
        warning.setReadFlag(false);
        boolean save = save(warning);
        addUnread(warning.getId());
        return save;
    }

    private void addUnread(Long id) {
        List<Client> list = clientService.list();
        if (list == null || list.isEmpty()) {
            return;
        }
        List<LogMessageAction> msgs = list.stream().map(c -> {
            LogMessageAction action = new LogMessageAction();
            action.setMessageId(id);
            action.setUserId(c.getId());
            action.setType(ContentActionEnum.UNREAD.getCode());
            return action;
        }).collect(Collectors.toList());

        messageActionService.saveBatch(msgs);
    }

    @Override
    public void read(List<Long> warningIds, Long userId) {
        LambdaUpdateWrapper<LogMessageAction> query = Wrappers.lambdaUpdate(LogMessageAction.class);
        query.eq(LogMessageAction::getUserId, userId);
        query.in(LogMessageAction::getId, warningIds);

        query.set(LogMessageAction::getType, ContentActionEnum.READ.getCode());

        messageActionService.update(query);
    }
}