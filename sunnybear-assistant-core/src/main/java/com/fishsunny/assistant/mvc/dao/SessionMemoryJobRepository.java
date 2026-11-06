package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage 自动记忆整理待办的数据访问接口
 *
 *        表里只有待办：每轮对话续期（upsert），整理时删行即认领。不存状态、不存结果。
 *
 *        会话被删除后这里不主动清理：残留行会在到期时被 take 删掉，再由执行端发现会话已不存在而跳过，
 *        自己收敛。所以删除会话的链路不需要认识这张表。
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

    /** 续期：会话已有待办就覆盖到期时间与计时依据，没有则插入 */
    void upsert(SessionMemoryJob job);

    /**
     * 认领并消费这一轮待办：按「会话 + 计时依据」删行。
     * <p>删到 1 行 = 归我跑；删到 0 行 = 已被别处拿走，或这期间用户又说话了（计时依据已换成新的）。
     *
     * @return 是否认领成功
     */
    boolean take(String sessionId, LocalDateTime lastMessageTime);

    /**
     * 到期待办（due_time &lt;= now），按到期先后排序。
     * <p>due_time 存的是 yyyy-MM-dd HH:mm:ss 文本，字典序即时间序，可以直接比较。
     */
    List<SessionMemoryJob> selectDue(LocalDateTime now);
}
