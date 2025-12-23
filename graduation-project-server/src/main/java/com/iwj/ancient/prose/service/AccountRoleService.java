package com.iwj.ancient.prose.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.iwj.ancient.prose.entity.AccountRole;

/**
 * <p>
 * 用户角色 服务类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
public interface AccountRoleService extends IService<AccountRole> {

    void removeByAccount(Long accountId);
}
