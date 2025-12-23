package com.iwj.ancient.prose.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.iwj.ancient.prose.entity.Action;

import java.util.List;

/**
 * <p>
 * 阅读&点赞 服务类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
public interface ActionService extends IService<Action> {
    Long findLatest(Long userId);

    void read(Long contentId, Long userId);

    void unRead(Long contentId, List<Long> userIds);

    boolean validDelete(List<Long> contentIds);
}