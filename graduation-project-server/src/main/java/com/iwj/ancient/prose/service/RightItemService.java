package com.iwj.ancient.prose.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.iwj.ancient.prose.dto.RoleDto;
import com.iwj.ancient.prose.entity.RightItem;

import java.util.List;

/**
 * <p>
 * 权限项 服务类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
public interface RightItemService extends IService<RightItem> {
    List<RoleDto.Item> findByRoleId(List<Long> roleIds);
    List<String> findByAccountId(Long accountId);
}
