package com.iwj.ancient.prose.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 全局配置
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("`oms_dict`")
public class Dict extends Model<Dict> {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "`id`", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 状态:enable,disable
     */
    @TableField("`status`")
    private String status;

    /**
     * 类型
     */
    @TableField("`type`")
    private String type;

    /**
     * 配置项
     */
    @TableField("`key`")
    private String key;

    /**
     * 配置值
     */
    @TableField("`value`")
    private String value;

    /**
     * 配置说明
     */
    @TableField("`remark`")
    private String remark;

    /**
     * 创建时间
     */
    @TableField(value = "`createTime`", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更细时间
     */
    @TableField(value = "`updateTime`", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 创建人
     */
    @TableField(value = "`createBy`", fill = FieldFill.INSERT)
    private Long createBy;

    /**
     * 更新人
     */
    @TableField(value = "`updateBy`", fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    /**
     * 逻辑删除
     */
    @TableField(value = "`enableFlag`", fill = FieldFill.INSERT)
    @TableLogic
    private Boolean enableFlag;


    @Override
    protected Serializable pkVal() {
        return this.id;
    }

}
