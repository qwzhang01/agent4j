package com.iwj.ancient.prose.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iwj.ancient.prose.dto.LogMessageDto;
import com.iwj.ancient.prose.entity.LogMessage;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

/**
 * <p>
 * 站内信 Mapper 接口
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Repository
public interface LogMessageMapper extends BaseMapper<LogMessage> {
    Page<LogMessageDto.List> list(Page<LogMessage> page, @Param("type") String type, @Param("userId") Long userId);
}

