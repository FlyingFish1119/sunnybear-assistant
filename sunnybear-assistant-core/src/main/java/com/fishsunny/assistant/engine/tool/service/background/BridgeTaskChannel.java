package com.fishsunny.assistant.engine.tool.service.background;

/*
 * @Usage 桥接任务的取消通道。
 *        CommandTaskRegistry 在收到 BRIDGE_COMMAND / BRIDGE_SCRIPT 类型任务的结束请求时，
 *        经本接口把 cancel_task 下发到对应卫星机；本机类型（COMMAND / SCRIPT）不走这里。
 *
 *        实现方为卫星桥接服务（plug.satellite.service.SatelliteBridgeService）。
 *        经 ObjectProvider 注入以避免登记表与桥接服务之间的构造环；实现方缺失（未装配）时
 *        视为送达失败，登记表会本地摘除该任务，避免登记悬死。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/23
 */
public interface BridgeTaskChannel {

    /**
     * 向指定卫星机下发 cancel_task。
     *
     * @return true 表示已送达且卫星机报告任务存在并已对其执行终止；
     *         false 表示送达失败（设备不在线 / 超时）或卫星端已无此任务
     */
    boolean dispatchCancel(String deviceId, String taskId);
}
