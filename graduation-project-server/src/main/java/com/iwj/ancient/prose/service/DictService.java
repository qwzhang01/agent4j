package com.iwj.ancient.prose.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.iwj.ancient.prose.dto.DictDto;
import com.iwj.ancient.prose.entity.Dict;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 全局配置 服务类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
public interface DictService extends IService<Dict> {

    List<DictDto.Select> findByType(String type);

    List<DictDto.Select> findByTypes(List<String> types);

    Map<String, String> findMapByType(String type);

    String findValue(String type, String key);

    Boolean put(String type, String key, String value);

    boolean validKey(String type, String... value);
}
