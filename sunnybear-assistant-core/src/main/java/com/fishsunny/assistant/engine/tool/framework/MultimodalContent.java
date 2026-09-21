package com.fishsunny.assistant.engine.tool.framework;

/*
 * @Usage 多模态工具结果内容块。工具把 base64 数据填进 data，
 *        实现 {@link MultimodalResultHandler} 后由工具自行落盘，
 *        落盘后 path 由 MultimodalResultAble 回写为可移植引用（"sessionId:fileName"），
 *        供外层读取后经 SessionFileManager.loadSessionFile 转 data URI。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 */

import com.fishsunny.assistant.engine.ContentType;
import lombok.Data;

/**
 * 多模态工具结果内容块。
 */
@Data
public class MultimodalContent {

    /** 文件引用：工具产出时为会话内文件名，落盘后被回写为 "sessionId:fileName" */
    private String pathOrText;

    /** 内容类型常量，见 {@link ContentType}：image / audio / video / text / file */
    private String type;

    /** base64 数据 */
    private String data;

    public MultimodalContent() {
    }

    public MultimodalContent(String pathOrText, String type, String data) {
        this.pathOrText = pathOrText;
        this.type = type;
        this.data = data;
    }
}
