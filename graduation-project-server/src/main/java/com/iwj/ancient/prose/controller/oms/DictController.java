package com.iwj.ancient.prose.controller.oms;

import com.iwj.ancient.prose.dto.DictDto;
import com.iwj.ancient.prose.service.DictService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

@RestController
@RequestMapping("api/oms/dict")
@Slf4j
@AllArgsConstructor
public class DictController {

    private final DictService dictService;

    @PostMapping
    public List<DictDto.Select> list(@RequestBody @Valid @NotNull(message = "类型不能为空") @Size(min = 1, message = "类型不能为空") List<String> types) {
        return dictService.findByTypes(types);
    }

    @GetMapping("{type}")
    public List<DictDto.Select> get(@PathVariable("type") String type) {
        return dictService.findByType(type);
    }

    @PutMapping("{type}/{key}")
    public Boolean put(@PathVariable("type") String type, @PathVariable("key") String key, @RequestBody String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        return dictService.put(type, key, value);
    }
}