package com.fishsunny.assistant.engine.protocol.project.entity.message.content;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 20:28
 */

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fishsunny.assistant.constants.ContentTypeVariable;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.audio.AudioContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.file.FileContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.video.VideoContent;
import com.fishsunny.assistant.engine.tool.framework.MultimodalContent;
import com.fishsunny.assistant.utils.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

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
public abstract class MessageContent {

    public static final Logger log = LoggerFactory.getLogger(MessageContent.class);

    public static List<MessageContent> files(List<String> filePaths) {
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

    public static List<MessageContent> fillFiles(List<MessageContent> contents) {
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
                String url = imageContent.getUrl();
                // 已是 data URI（如多模态 tool 结果经同轮复用），直接透传，无需读取本地文件
                if (url.startsWith("data:")) {
                    messageContents.add(new ImageContent(url));
                    continue;
                }
                File file = new File(url);
                if (!file.exists()) {
                    continue;
                }
                try (FileInputStream inputStream = new FileInputStream(file)) {
                    byte[] bytes = inputStream.readAllBytes();
                    String dataUrl = ObjectUtils.encodeToDataUrl(imageContent.getUrl(), bytes);
                    messageContents.add(new ImageContent(dataUrl));
                } catch (Exception e) {
                    log.error("Error loading image file: {}", url, e);
                }
            }
            if (content instanceof VideoContent videoContent) {
                String url = videoContent.getUrl();
                if (url.startsWith("data:")) {
                    messageContents.add(new VideoContent(url));
                    continue;
                }
                File file = new File(url);
                if (!file.exists()) {
                    continue;
                }
                try (FileInputStream inputStream = new FileInputStream(file)) {
                    byte[] bytes = inputStream.readAllBytes();
                    String dataUrl = ObjectUtils.encodeToDataUrl(videoContent.getUrl(), bytes);
                    messageContents.add(new VideoContent(dataUrl));
                } catch (Exception e) {
                    log.error("Error loading video file: {}", url, e);
                }
            }
            if (content instanceof AudioContent audioContent) {
                String url = audioContent.getUrl();
                if (url.startsWith("data:")) {
                    messageContents.add(new AudioContent(url));
                    continue;
                }
                File file = new File(url);
                if (!file.exists()) {
                    continue;
                }
                try (FileInputStream inputStream = new FileInputStream(file)) {
                    byte[] bytes = inputStream.readAllBytes();
                    String dataUrl = ObjectUtils.encodeToDataUrl(audioContent.getUrl(), bytes);
                    messageContents.add(new AudioContent(dataUrl));
                } catch (Exception e) {
                    log.error("Error loading audio file: {}", url, e);
                }
            }
            if (content instanceof FileContent fileContent) {
                String path = fileContent.getUrl();
                if (! ObjectUtils.canHumanReadFile(path)) {
                    messageContents.add(new TextContent("用户上传了文件：" + fileContent.getUrl()));
                    continue;
                }
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


    /**
     * 把多模态内容列表转换为项目 MessageContent 列表。
     */
    public static List<MessageContent> toMessageContents(List<MultimodalContent> contents) {
        List<MessageContent> messageContents = new ArrayList<>();
        if (CollectionUtils.isEmpty(contents)) {
            return messageContents;
        }
        for (MultimodalContent content : contents) {
            String data = content.getPath();
            if (!StringUtils.hasText(data)) {
                continue;
            }
            switch (content.getType() == null ? "" : content.getType()) {
                case ContentTypeVariable.IMAGE -> messageContents.add(new ImageContent(data));
                case ContentTypeVariable.AUDIO -> messageContents.add(new AudioContent(data));
                case ContentTypeVariable.VIDEO -> messageContents.add(new VideoContent(data));
                case ContentTypeVariable.TEXT -> messageContents.add(new TextContent(data));
                default -> messageContents.add(new FileContent(data));
            }
        }
        return messageContents;
    }
}
