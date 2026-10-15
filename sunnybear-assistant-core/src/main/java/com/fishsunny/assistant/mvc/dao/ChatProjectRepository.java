package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage 项目仓储 —— chat_project 表的读写。
 *        远程存储模式下由 @RemoteRepository 走仓库 RPC，本地模式走下面的 Implement。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/15
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatProject;
import com.fishsunny.assistant.remote.RemoteRepository;

import java.util.List;

@RemoteRepository(repoCls = ChatProjectRepository.class)
public interface ChatProjectRepository {

    public ChatProject insert(ChatProject project);

    /** 整体覆盖写入（name / description / color / update_time）；调用方负责先合并出完整对象 */
    public ChatProject update(ChatProject project);

    public ChatProject deleteById(String id);

    /** 全部项目，按 update_time 倒序（最近改动的在前） */
    public List<ChatProject> selectAll();

    public ChatProject selectById(String id);

    /** 按名称精确查（建项目 / 改名时的重名校验用）；不存在返回 null */
    public ChatProject selectByName(String name);
}
