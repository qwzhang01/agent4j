package com.iwj.ancient.prose.controller.oms;

import com.iwj.ancient.prose.dto.FileDto;
import com.iwj.ancient.prose.kit.FileKit;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("api/oms/file")
@Slf4j
@AllArgsConstructor
public class FileController {
    private final FileKit fileKit;

    @PostMapping(value = "up-file")
    public FileDto.Info uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String contentType = file.getContentType();
        String url = fileKit.uploadByte(bytes, contentType, file.getOriginalFilename());
        FileDto.Info info = new FileDto.Info();
        info.setUrl(url);
        info.setName(file.getOriginalFilename());
        info.setSize(file.getSize());
        return info;
    }

    @PostMapping(value = "up-base64")
    public FileDto.Info uploadBase64(@RequestBody @Valid FileDto.Base64 base64) {
        byte[] bytes = base64.getBase64().getBytes(StandardCharsets.UTF_8);
        String url = fileKit.uploadByte(bytes, null, base64.getName());
        FileDto.Info info = new FileDto.Info();
        info.setUrl(url);
        info.setName(base64.getName());
        info.setSize(base64.getSize());
        return info;
    }
}