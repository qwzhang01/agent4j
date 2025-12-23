package com.iwj.ancient.prose.controller.oms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iwj.ancient.prose.common.ActionDesc;
import com.iwj.ancient.prose.dto.ClientDto;
import com.iwj.ancient.prose.dto.PageQuery;
import com.iwj.ancient.prose.entity.Client;
import com.iwj.ancient.prose.service.ClientService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Collectors;

@RestController
@RequestMapping("api/oms/client")
@Slf4j
@AllArgsConstructor
@ActionDesc(desc = "OMS 客户")
public class ClientController {
    private final ClientService clientService;

    @PostMapping("list")
    public Page<ClientDto.Info> list(@RequestBody PageQuery query) {
        LambdaQueryWrapper<Client> lqw = Wrappers.lambdaQuery(Client.class);
        if (StringUtils.isNotBlank(query.getName())) {
            lqw.eq(Client::getAccount, query.getName());
        }
        lqw.orderByDesc(Client::getId);
        Page<Client> page = clientService.page(new Page<>(query.getCurrent(), query.getSize()), lqw);
        Page<ClientDto.Info> result = new Page<>(page.getCurrent(), page.getSize(), page.getCurrent());
        result.setRecords(page.getRecords().stream().map(s -> {
            ClientDto.Info info = new ClientDto.Info();
            BeanUtils.copyProperties(s, info);
            info.setAccount(s.getAccount());
            return info;
        }).collect(Collectors.toList()));
        return result;
    }
}
