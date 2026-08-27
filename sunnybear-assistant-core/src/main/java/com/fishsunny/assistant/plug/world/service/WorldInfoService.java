package com.fishsunny.assistant.plug.world.service;

/*
 * @Usage 世界观服务接口
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldInfo;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface WorldInfoService {

    WorldInfo findById(String id);

    List<WorldInfo> findAll();

    WorldInfo save(WorldInfo worldInfo) throws IOException;

    WorldInfo update(WorldInfo worldInfo) throws IOException;

    /** 删除世界观（级联删除其下全部角色 + 清理文件目录），返回被删的世界观 */
    WorldInfo deleteById(String id);

    /** 单独删除世界观背景图（文件 + 清空 DB 字段），返回更新后的世界观 */
    WorldInfo deleteBackground(String id);

    /** 单独上传世界观背景图（缩放 + 写文件 + 更新 DB），返回文件路径 */
    String uploadBackground(String id, MultipartFile file);
}
