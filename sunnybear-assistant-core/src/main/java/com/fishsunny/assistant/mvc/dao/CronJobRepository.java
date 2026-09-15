package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage 定时任务数据访问接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/29
 */

import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;
import com.fishsunny.assistant.remote.RemoteRepository;

import java.util.List;

@RemoteRepository(repoCls = CronJobRepository.class)
public interface CronJobRepository {

    CronJob insert(CronJob cronJob);

    CronJob update(CronJob cronJob);

    CronJob deleteById(Integer id);

    CronJob selectById(Integer id);

    List<CronJob> selectAll();
}
