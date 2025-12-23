package com.iwj.ancient.prose.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.iwj.ancient.prose.entity.Role;

import java.util.List;

/**
 * <p>
 * 角色 服务类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
public interface RoleService extends IService<Role> {
    List<Role> findByAccountId(Long accountId);

    void addStaff(Long accountId, String roleName);
}
