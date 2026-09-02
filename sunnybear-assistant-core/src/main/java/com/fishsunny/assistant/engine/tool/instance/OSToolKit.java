package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 00:53
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.os.enable", havingValue = "true", matchIfMissing = true)
public class OSToolKit extends ToolKit {

    public OSToolKit(List<ToolHandler> tools, @Value("${engine.tool.os.enable:true}") boolean enable) {
        super(tools, enable);
    }

    public static void writeLog(Path logFile, Process process) throws IOException, InterruptedException {
        try (InputStream inputStream = process.getInputStream();
             OutputStream outputStream = Files.newOutputStream(logFile,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                outputStream.flush();
            }
        }
    }
}
