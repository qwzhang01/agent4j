package com.iwj.ancient.prose.dto;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import lombok.Data;

import java.util.List;

@Data
public class PageQuery {
    private long size = 10;
    private long current = 1;
    private String name;
    private List<OrderItem> orders;
}
