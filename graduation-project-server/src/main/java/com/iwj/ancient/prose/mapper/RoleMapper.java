package com.iwj.ancient.prose.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iwj.ancient.prose.entity.Role;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * <p>
 * 角色 Mapper 接口
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Repository
public interface RoleMapper extends BaseMapper<Role> {
    List<Role> findByAccountId(@Param("accountId") Long accountId);
}
