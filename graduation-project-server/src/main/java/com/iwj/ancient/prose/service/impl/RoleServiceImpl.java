package com.iwj.ancient.prose.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iwj.ancient.prose.entity.AccountRole;
import com.iwj.ancient.prose.entity.Role;
import com.iwj.ancient.prose.mapper.RoleMapper;
import com.iwj.ancient.prose.service.AccountRoleService;
import com.iwj.ancient.prose.service.RoleService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <p>
 * 角色 服务实现类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Service
@Slf4j
@AllArgsConstructor
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements RoleService {
    private AccountRoleService accountRoleService;

    @Override
    public List<Role> findByAccountId(Long accountId) {
        return baseMapper.findByAccountId(accountId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void addStaff(Long accountId, String roleName) {
        Long roleId = getByName(roleName);
        accountRoleService.removeByAccount(accountId);
        AccountRole accountRole = new AccountRole();
        accountRole.setAccountId(accountId);
        accountRole.setRoleId(roleId);
        accountRoleService.save(accountRole);
    }

    private Long getByName(String name) {
        LambdaQueryWrapper<Role> query = Wrappers.lambdaQuery(Role.class);
        query.eq(Role::getName, name);
        query.select(Role::getId);
        return getObj(query, v -> Long.parseLong(String.valueOf(v)));
    }
}
