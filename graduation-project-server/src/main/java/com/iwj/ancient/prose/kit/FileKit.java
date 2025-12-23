package com.iwj.ancient.prose.kit;

import com.iwj.ancient.prose.common.AppProperty;
import com.iwj.ancient.prose.exception.AncientException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;

@Slf4j
@Component
@AllArgsConstructor
public class FileKit {
    private final AppProperty appProperty;

    /**
     * 将文件存至指定位置
     *
     * @param bytes
     * @param contentType
     * @param name
     * @return
     */
    public String uploadByte(byte[] bytes, String contentType, String name) {
        try {
            OutputStream outputStream = Files.newOutputStream(new File(appProperty.getFileLocate() + "/" + name).toPath());
            outputStream.write(bytes, 0, bytes.length);
            outputStream.close();
            return appProperty.getFileLocate() + "/" + name;
        } catch (Exception e) {
            throw new AncientException(e);
        }
    }
}
