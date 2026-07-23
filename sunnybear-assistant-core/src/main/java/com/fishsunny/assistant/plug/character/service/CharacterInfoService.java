package com.fishsunny.assistant.plug.character.service;

/*
 * @Usage 角色信息服务接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface CharacterInfoService {

    CharacterInfo findById(String id);

    List<CharacterInfo> findAll();

    CharacterInfo save(CharacterInfo characterInfo) throws IOException;

    CharacterInfo update(CharacterInfo characterInfo) throws IOException;

    CharacterInfo deleteById(String id);

    /** 单独删除角色背景图（文件 + 清空 DB 字段），返回更新后的角色 */
    CharacterInfo deleteBackground(String id);

    /** 单独上传角色背景图（缩放 + 写文件 + 更新 DB），返回文件路径 */
    String uploadBackground(String id, MultipartFile file);
}
