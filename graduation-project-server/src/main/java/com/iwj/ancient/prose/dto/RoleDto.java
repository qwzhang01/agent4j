package com.iwj.ancient.prose.dto;

import lombok.Data;

import java.util.List;

public class RoleDto {
    @Data
    public static class Info {
        private Long id;
        private String name;
        private String desc;
        private List<Item> rightItems;
    }

    @Data
    public static class Item {
        private Long id;
        private Long roleId;
        private String name;
        private Long parentId;
    }
}
