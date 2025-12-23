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
 * 权限项
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("`oms_right_item`")
public class RightItem extends Model<RightItem> {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "`id`", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 名称
     */
    @TableField("`name`")
    private String name;

    /**
     * 上级ID
     */
    @TableField("`parentId`")
    private Long parentId;

    /**
     * 一级菜单&二级菜单&按钮
     */
    @TableField("`menuType`")
    private String menuType;

    /**
     * 权限标识
     */
    @TableField("`itemCode`")
    private String itemCode;


    @Override
    protected Serializable pkVal() {
        return this.id;
    }

}
