package com.iwj.ancient.prose.controller.oms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iwj.ancient.prose.common.ActionDesc;
import com.iwj.ancient.prose.dto.LogActionDto;
import com.iwj.ancient.prose.entity.LogAction;
import com.iwj.ancient.prose.service.LogActionService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.stream.Collectors;

@RestController
@RequestMapping("api/oms/log/action")
@Slf4j
@AllArgsConstructor
@ActionDesc(desc = "OMS 操作日志")
public class LogActionController {

    private final LogActionService actionService;

    @PostMapping(value = "list")
    public Page<LogActionDto.List> list(@RequestBody @Valid LogActionDto.Query query) {
        LambdaQueryWrapper<LogAction> queryWrapper = Wrappers.lambdaQuery(LogAction.class);
        if (StringUtils.isNotBlank(query.getContent())) {
            queryWrapper.like(LogAction::getModule, query.getContent()).or().like(LogAction::getActionDesc, query.getContent());
        }
        queryWrapper.orderByDesc(LogAction::getId);
        Page<LogAction> page = actionService.page(new Page<>(query.getCurrent(), query.getSize()), queryWrapper);
        Page<LogActionDto.List> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        if (page.getRecords() != null) {
            result.setRecords(page.getRecords().stream().map(b -> {
                LogActionDto.List list = new LogActionDto.List();
                BeanUtils.copyProperties(b, list);
                return list;
            }).collect(Collectors.toList()));
        }
        return result;
    }
}