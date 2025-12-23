package com.iwj.ancient.prose.controller.oms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iwj.ancient.prose.common.ActionDesc;
import com.iwj.ancient.prose.dto.PageQuery;
import com.iwj.ancient.prose.dto.RoleDto;
import com.iwj.ancient.prose.entity.Role;
import com.iwj.ancient.prose.service.RightItemService;
import com.iwj.ancient.prose.service.RoleService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("api/oms/role")
@Slf4j
@AllArgsConstructor
@ActionDesc(desc = "OMS 角色")
public class RoleController {

    private final RoleService roleService;
    private RightItemService rightItemService;

    @PostMapping(value = "list")
    public Page<RoleDto.Info> list(@RequestBody @Valid PageQuery query) {
        LambdaQueryWrapper<Role> lqw = Wrappers.lambdaQuery(Role.class);
        if (StringUtils.isNotBlank(query.getName())) {
            lqw.like(Role::getName, query.getName());
        }
        lqw.orderByDesc(Role::getId);
        Page<Role> page = roleService.page(new Page<>(query.getCurrent(), query.getSize()), lqw);

        List<RoleDto.Item> items = rightItemService.findByRoleId(page.getRecords().stream().map(Role::getId).collect(Collectors.toList()));
        Page<RoleDto.Info> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        Map<Long, List<RoleDto.Item>> map = items.stream().collect(Collectors.groupingBy(RoleDto.Item::getRoleId));
        result.setRecords(page.getRecords().stream().map(r -> {
            RoleDto.Info info = new RoleDto.Info();
            BeanUtils.copyProperties(r, info);
            info.setRightItems(map.get(info.getId()));
            return info;
        }).collect(Collectors.toList()));
        return result;
    }
}