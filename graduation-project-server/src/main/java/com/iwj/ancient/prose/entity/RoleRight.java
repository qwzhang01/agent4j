package com.iwj.ancient.prose.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * <p>
 * 角色权限项
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("`oms_role_right`")
public class RoleRight extends Model<RoleRight> {

    private static final long serialVersionUID = 1L;

    /**
     * 角色ID
     */
    @TableId(value = "`roleId`", type = IdType.ASSIGN_ID)
    private Long roleId;

    /**
     * 权限项ID
     */
    @TableField("`rightId`")
    private Long rightId;


    @Override
    protected Serializable pkVal() {
        return this.roleId;
    }

}
