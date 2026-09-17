package com.fishsunny.assistant.engine.tool.framework;

import com.fishsunny.assistant.engine.tool.ToolExecutor;

import java.util.List;
import java.util.Map;

/**
 * 工具响应后置处理链 —— 在一次 {@code execute} 中所有工具执行完成后依次回调，用于统一改写/补充工具结果。
 * <p>
 * 入参直接给出本批次的全部工具响应（顺序与请求一致），处理链可据此判断本轮调用了哪些工具、
 * 以及各自结果，并自行决定把补充内容写进哪一条（例如只追加到最后一条，避免同轮重复）。
 * 链执行完由 {@link ToolExecutor} 对被改写的响应补推一帧，保证前端实时可见。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/17 15:42
 */
public interface ToolResponseHandleChain {

    void handle(List<ToolExecutor.ToolExecuteResponse> batchResponses, Map<String, Object> context);

    int getOrder();
}
