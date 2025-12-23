package com.iwj.ancient.prose.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iwj.ancient.prose.dto.RoleDto;
import com.iwj.ancient.prose.entity.RightItem;
import com.iwj.ancient.prose.mapper.RightItemMapper;
import com.iwj.ancient.prose.service.RightItemService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * <p>
 * 权限项 服务实现类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Service
public class RightItemServiceImpl extends ServiceImpl<RightItemMapper, RightItem> implements RightItemService {
    @Override
    public List<RoleDto.Item> findByRoleId(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        return baseMapper.findByRoleId(roleIds);
    }

    @Override
    public List<String> findByAccountId(Long accountId) {
        return baseMapper.findByAccountId(accountId);
    }
}
