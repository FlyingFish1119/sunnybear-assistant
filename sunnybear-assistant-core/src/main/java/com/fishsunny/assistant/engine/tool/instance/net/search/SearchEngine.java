package com.fishsunny.assistant.engine.tool.instance.net.search;

/*
 * @Usage 搜索引擎抽象接口
 *
 * 每种搜索引擎实现此接口，负责：
 * 1. 将统一参数（query/size/scope）适配为各自 API 的请求格式
 * 2. 调用 API 并返回原始结果（给 AI 阅读，无需结构化转换）
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/6
 */

public interface SearchEngine {

    /**
     * 返回引擎名称标识，如 "metaso"、"serper"
     */
    String getName();

    /**
     * 执行搜索，返回原始 JSON 字符串。
     * 各引擎自行将参数适配为对应 API 的请求格式。
     *
     * @param query 搜索关键词
     * @param size  返回条目数
     * @param scope 搜索范围（各引擎按自身能力处理）
     * @return API 原始响应 JSON
     * @throws Exception 搜索失败时抛出
     */
    String search(String query, int size, String scope) throws Exception;
}
