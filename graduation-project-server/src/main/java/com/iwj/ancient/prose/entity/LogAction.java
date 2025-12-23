package com.iwj.ancient.prose.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 操作记录
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("`oms_log_action`")
public class LogAction extends Model<LogAction> {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "`id`", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 操作时间
     */
    @TableField("`actionTime`")
    private LocalDateTime actionTime;

    /**
     * 耗时
     */
    @TableField("`timeConsuming`")
    private BigDecimal timeConsuming;

    /**
     * 客户端IP
     */
    @TableField("`clientIp`")
    private String clientIp;

    /**
     * 操作模块
     */
    @TableField("`module`")
    private String module;

    /**
     * 请求URL
     */
    @TableField("`url`")
    private String url;

    /**
     * 操作用户账户
     */
    @TableField("`account`")
    private String account;

    /**
     * 用户系统以及浏览器信息
     */
    @TableField("`userAgent`")
    private String userAgent;

    /**
     * 操作内容
     */
    @TableField("`actionDesc`")
    private String actionDesc;


    @Override
    protected Serializable pkVal() {
        return this.id;
    }

}
