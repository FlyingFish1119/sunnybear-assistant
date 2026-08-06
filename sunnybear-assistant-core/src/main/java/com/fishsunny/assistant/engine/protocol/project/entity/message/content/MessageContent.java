package com.fishsunny.assistant.engine.protocol.project.entity.message.content;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 20:28
 */

import com.fishsunny.assistant.engine.protocol.project.entity.message.content.audio.AudioContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.file.FileContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.video.VideoContent;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.variable.ContentTypeVariable;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,  // 类型信息作为 JSON 的一个属性
        property = "type"  // 使用 JSON 字段区分类型
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextContent.class, name = ContentTypeVariable.TEXT),
        @JsonSubTypes.Type(value = ImageContent.class, name = ContentTypeVariable.IMAGE),
        @JsonSubTypes.Type(value = VideoContent.class, name = ContentTypeVariable.VIDEO),
        @JsonSubTypes.Type(value = AudioContent.class, name = ContentTypeVariable.AUDIO),
        @JsonSubTypes.Type(value = FileContent.class, name = ContentTypeVariable.FILE)
})
public interface MessageContent {

    public static final Logger log = LoggerFactory.getLogger(MessageContent.class);

    public static List<MessageContent> loadFileContent(List<String> filePaths) {
        if (filePaths == null) {
            return new ArrayList<>();
        }
        List<MessageContent> messageContents = new ArrayList<>();
        for (String fileName: filePaths) {
            String type = ObjectUtils.detectFileTypeByExtension(fileName);
            switch (type) {
                case ContentTypeVariable.IMAGE:
                    messageContents.add(new ImageContent(fileName));
                    break;
                case ContentTypeVariable.VIDEO:
                    messageContents.add(new VideoContent(fileName));
                    break;
                case ContentTypeVariable.AUDIO:
                    messageContents.add(new AudioContent(fileName));
                    break;
                default:
                    messageContents.add(new FileContent(fileName));
            }
        }
        return messageContents;
    }

    public static List<MessageContent> fillFile(List<MessageContent> contents) {
        if (contents == null) {
            return new ArrayList<>();
        }
        List<MessageContent> messageContents = new ArrayList<>();
        for (MessageContent content: contents) {
            if (content instanceof TextContent textContent) {
                messageContents.add(new TextContent(textContent.getContent()));
                continue;
            }
            if (content instanceof ImageContent imageContent) {
                String path = imageContent.getUrl();
                File file = new File(path);
                if (!file.exists()) {
                    continue;
                }
                try (FileInputStream inputStream = new FileInputStream(file)) {
                    byte[] bytes = inputStream.readAllBytes();
                    String dataUrl = ObjectUtils.encodeToDataUrl(imageContent.getUrl(), bytes);
                    messageContents.add(new ImageContent(dataUrl));
                } catch (Exception e) {
                    log.error("Error loading image file: {}", path, e);
                }
            }
            if (content instanceof VideoContent videoContent) {
                String path = videoContent.getUrl();
                File file = new File(path);
                if (!file.exists()) {
                    continue;
                }
                try (FileInputStream inputStream = new FileInputStream(file)) {
                    byte[] bytes = inputStream.readAllBytes();
                    String dataUrl = ObjectUtils.encodeToDataUrl(videoContent.getUrl(), bytes);
                    messageContents.add(new VideoContent(dataUrl));
                } catch (Exception e) {
                    log.error("Error loading video file: {}", path, e);
                }
            }
            if (content instanceof AudioContent audioContent) {
                String path = audioContent.getUrl();
                File file = new File(path);
                if (!file.exists()) {
                    continue;
                }
                try (FileInputStream inputStream = new FileInputStream(file)) {
                    byte[] bytes = inputStream.readAllBytes();
                    String dataUrl = ObjectUtils.encodeToDataUrl(audioContent.getUrl(), bytes);
                    messageContents.add(new AudioContent(dataUrl));
                } catch (Exception e) {
                    log.error("Error loading audio file: {}", path, e);
                }
            }
            if (content instanceof FileContent fileContent) {
                String path = fileContent.getUrl();
                File file = new File(path);
                if (!file.exists()) {
                    continue;
                }
                try (FileInputStream inputStream = new FileInputStream(file)) {
                    messageContents.add(new TextContent(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    log.error("Error loading file: {}", path, e);
                }
            }
        }
        return messageContents;
    }
}
