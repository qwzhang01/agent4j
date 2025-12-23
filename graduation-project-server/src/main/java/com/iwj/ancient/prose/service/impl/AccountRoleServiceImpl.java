package com.iwj.ancient.prose.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iwj.ancient.prose.entity.AccountRole;
import com.iwj.ancient.prose.mapper.AccountRoleMapper;
import com.iwj.ancient.prose.service.AccountRoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 * 用户角色 服务实现类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Service
public class AccountRoleServiceImpl extends ServiceImpl<AccountRoleMapper, AccountRole> implements AccountRoleService {

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void removeByAccount(Long accountId) {
        LambdaQueryWrapper<AccountRole> query = Wrappers.lambdaQuery(AccountRole.class);
        query.eq(AccountRole::getAccountId, accountId);
        remove(query);
    }
}