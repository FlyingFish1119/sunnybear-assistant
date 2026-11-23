package com.fishsunny.assistant.plug.comfyui.config;

/*
 * @Usage ComfyUI 插件配置：卫星机与 ComfyUI 本体的位置全部由核心端维护，工具只负责生成命令。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/23
 */

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ComfyUI 相关配置，来自 application.yml 的 {@code plug.comfyui.*}。
 * <p>
 * 重构后不再需要专用代理：卫星机上装着 ComfyUI，核心端生成 Python 脚本经 exec 下发执行。
 * 因此这里只需要知道「哪台卫星机、ComfyUI 在哪个地址、工作流和输出目录在哪」。
 * </p>
 */
@Getter
@Component
public class ComfyUIConfig {

    /** 目标卫星机 deviceId */
    @Value("${plug.comfyui.device:}")
    private String device;

    /** ComfyUI 服务地址（从卫星机视角访问） */
    @Value("${plug.comfyui.base-url:http://127.0.0.1:8188}")
    private String baseUrl;

    /** API 格式工作流目录（卫星机本地绝对路径） */
    @Value("${plug.comfyui.workflow-dir:}")
    private String workflowDir;

    /** ComfyUI 模型根目录（卫星机本地绝对路径），资源查询直接扫这里 */
    @Value("${plug.comfyui.models-dir:}")
    private String modelsDir;

    /** ComfyUI 输出目录（卫星机本地绝对路径），生成图从这里拉回本机 */
    @Value("${plug.comfyui.output-dir:}")
    private String outputDir;

    /** 生图等待上限（秒） */
    @Value("${plug.comfyui.generate-timeout-s:1800}")
    private int generateTimeoutS;
}
