package com.fishsunny.assistant.engine.adapter;

/*
 * @Usage TTS 能力接口（好莱坞原则）：ChatHttpHandler 在 translate 的收流循环里按本接口回调
 *        adapter，引擎保持协议无关——回调传的是 API 自己的消息体（target response），
 *        adapter 自行强转到自己的 targetRespCls 取 typed 字段（不做 master 转换）。
 *        适配器的 TTSClient 由工厂在创建时注入（见 AIAdapterOption.ttsClient）。
 *        不实现本接口的 adapter 完全无感知。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/10
 */

import com.fishsunny.assistant.engine.protocol.AIResponse;

public interface HandleTTSAble {

    /**
     * 收流循环每个 chunk 之后由引擎回调（好莱坞）：adapter 把本 chunk 的可读正文喂入自己的
     * 断句缓冲；凑成句则**同步合成**并返回该批音频（mp3 base64，格式见 engine.tts.format），
     * 未成句返回 null。引擎拿到非 null 结果后会经 InTranslateCallback.onTTSAudio 按文本
     * 同节奏交还调用方。
     *
     * @param targetChunk engine 每行解析出的 target 原生对象（非 master 转换结果）
     * @param finished    本次流是否已结束（实现应在此冲刷剩余缓冲，把尾部也合成返回）
     * @return 本次可出句的音频 mp3 base64；无可读内容返回 null
     */
    default String onTTSChunk(AIResponse targetChunk, boolean finished) {
        return null;
    }

    /**
     * 整轮（一次 translate）收流结束后、complete 回调前由引擎调用：对整轮可读正文合成
     * 完整音频（mp3 base64），供调用方入库（如塞进 assistant 消息的 extension）。
     *
     * @return 完整音频 mp3 base64；无正文 / TTS 不可用返回 null
     */
    default String fullAudioBase64() {
        return null;
    }

    /**
     * 用户中断 / 连接错误 / 回合收尾兜底：丢弃断句缓冲。实现必须幂等——
     * 正常结束后调用本方法应无副作用。
     */
    default void cancelTTS() {
    }
}
