package com.iwj.ancient.prose.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iwj.ancient.prose.entity.LogAction;
import com.iwj.ancient.prose.mapper.LogActionMapper;
import com.iwj.ancient.prose.service.LogActionService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 操作记录 服务实现类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Service
public class LogActionServiceImpl extends ServiceImpl<LogActionMapper, LogAction> implements LogActionService {

}
