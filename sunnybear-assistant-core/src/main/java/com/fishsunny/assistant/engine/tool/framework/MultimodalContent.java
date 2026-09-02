package com.fishsunny.assistant.engine.tool.framework;

/*
 * @Usage 多模态工具结果内容块。工具把 base64 数据填进 data，
 *        实现 {@link MultimodalResultHandler} 后由工具自行落盘，
 *        落盘后 data 会被 replace 替换为文件路径，供外层读取后复用 fillFiles 转 data URI。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 */

import com.fishsunny.assistant.constants.ContentTypeVariable;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.audio.AudioContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.file.FileContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.video.VideoContent;
import lombok.Data;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 多模态工具结果内容块。
 */
@Data
public class MultimodalContent {

    /** 文件路径 */
    private String path;

    /** 内容类型常量，见 {@link ContentTypeVariable}：image / audio / video / text / file */
    private String type;

    /** base64 数据 */
    private String data;

    public MultimodalContent() {
    }

    public MultimodalContent(String path, String type, String data) {
        this.path = path;
        this.type = type;
        this.data = data;
    }
}
