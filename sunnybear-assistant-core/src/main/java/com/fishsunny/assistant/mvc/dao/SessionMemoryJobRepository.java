package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage 自动记忆整理待办的数据访问接口
 *
 *        表里只有待办：每轮对话续期（upsert），整理时删行即认领。不存状态、不存结果。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/6
 */

import com.fishsunny.assistant.engine.protocol.project.entity.SessionMemoryJob;
import com.fishsunny.assistant.remote.RemoteRepository;

import java.time.LocalDateTime;
import java.util.List;

@RemoteRepository(repoCls = SessionMemoryJobRepository.class)
public interface SessionMemoryJobRepository {

    /** 按会话查待办；不存在返回 null */
    SessionMemoryJob selectBySessionId(String sessionId);

    /** 全部待办（启动扫描重建定时器用） */
    List<SessionMemoryJob> selectAll();

    /** 续期：会话已有待办就覆盖到期时间与计时依据，没有则插入 */
    void upsert(SessionMemoryJob job);

    /**
     * 认领并消费这一轮待办：按「会话 + 计时依据」删行。
     * <p>删到 1 行 = 归我跑；删到 0 行 = 已被别处拿走，或这期间用户又说话了（计时依据已换成新的）。
     *
     * @return 是否认领成功
     */
    boolean take(String sessionId, LocalDateTime lastMessageTime);

    /** 会话被删除时清理残留待办 */
    int deleteBySessionId(String sessionId);
}
