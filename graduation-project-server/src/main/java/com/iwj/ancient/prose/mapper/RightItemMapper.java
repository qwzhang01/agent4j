package com.iwj.ancient.prose.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iwj.ancient.prose.dto.RoleDto;
import com.iwj.ancient.prose.entity.RightItem;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * <p>
 * 权限项 Mapper 接口
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Repository
public interface RightItemMapper extends BaseMapper<RightItem> {
    List<RoleDto.Item> findByRoleId(@Param("roleIds") List<Long> roleIds);
    List<String> findByAccountId(@Param("accountId") Long accountId);
}
