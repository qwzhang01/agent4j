package com.iwj.ancient.prose.service;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.iwj.ancient.prose.dto.LogMessageDto;
import com.iwj.ancient.prose.dto.enums.MessageTagEnum;
import com.iwj.ancient.prose.dto.enums.MessageTypeEnum;
import com.iwj.ancient.prose.entity.LogMessage;

import java.util.List;

/**
 * <p>
 * 站内信 服务类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
public interface LogMessageService extends IService<LogMessage> {
    Page<LogMessageDto.List> list(Page<LogMessage> page, String type);

    Boolean add(MessageTagEnum tag, MessageTypeEnum type, Long targetId, Long userId, String title, String profile);

    void read(List<Long> warningIds, Long userId);
}