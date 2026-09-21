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
import com.fishsunny.assistant.engine.ContentType;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.audio.AudioContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.file.FileContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.video.VideoContent;
import com.fishsunny.assistant.engine.tool.framework.MultimodalContent;
import com.fishsunny.assistant.utils.ObjectUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,  // 类型信息作为 JSON 的一个属性
        property = "type"  // 使用 JSON 字段区分类型
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextContent.class, name = ContentType.TEXT),
        @JsonSubTypes.Type(value = ImageContent.class, name = ContentType.IMAGE),
        @JsonSubTypes.Type(value = VideoContent.class, name = ContentType.VIDEO),
        @JsonSubTypes.Type(value = AudioContent.class, name = ContentType.AUDIO),
        @JsonSubTypes.Type(value = FileContent.class, name = ContentType.FILE)
})
public abstract class MessageContent {
    public static List<MessageContent> files(List<String> filePaths) {
        if (filePaths == null) {
            return new ArrayList<>();
        }
        List<MessageContent> messageContents = new ArrayList<>();
        for (String fileName: filePaths) {
            String type = ObjectUtils.detectFileTypeByExtension(fileName);
            switch (type) {
                case ContentType.IMAGE:
                    messageContents.add(new ImageContent(fileName));
                    break;
                case ContentType.VIDEO:
                    messageContents.add(new VideoContent(fileName));
                    break;
                case ContentType.AUDIO:
                    messageContents.add(new AudioContent(fileName));
                    break;
                default:
                    messageContents.add(new FileContent(fileName));
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
            String data = content.getPathOrText();
            if (!StringUtils.hasText(data)) {
                continue;
            }
            switch (content.getType() == null ? "" : content.getType()) {
                case ContentType.IMAGE -> messageContents.add(new ImageContent(data));
                case ContentType.AUDIO -> messageContents.add(new AudioContent(data));
                case ContentType.VIDEO -> messageContents.add(new VideoContent(data));
                case ContentType.TEXT -> messageContents.add(new TextContent(data));
                default -> messageContents.add(new FileContent(data));
            }
        }
        return messageContents;
    }
}
