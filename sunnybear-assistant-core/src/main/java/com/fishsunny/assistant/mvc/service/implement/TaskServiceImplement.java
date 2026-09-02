package com.fishsunny.assistant.mvc.service.implement;

/*
 * @Usage 任务与步骤服务实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 07:20
 */

import com.fishsunny.assistant.engine.protocol.project.entity.Task;
import com.fishsunny.assistant.engine.protocol.project.entity.TaskStep;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.dao.TaskRepository;
import com.fishsunny.assistant.mvc.dao.TaskStepRepository;
import com.fishsunny.assistant.mvc.service.TaskService;
import com.fishsunny.assistant.mvc.service.validator.TaskValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@Service
public class TaskServiceImplement implements TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskServiceImplement.class);

    private final TaskRepository taskRepository;
    private final TaskStepRepository taskStepRepository;

    @Autowired
    public TaskServiceImplement(TaskRepository taskRepository, TaskStepRepository taskStepRepository) {
        this.taskRepository = taskRepository;
        this.taskStepRepository = taskStepRepository;
    }

    @Override
    @Transactional
    public TheTask createTheTask(Task task, List<TaskStep> taskSteps) {
        TaskValidator.createTask(task);
        if (taskSteps == null) {
            throw new UserException("任务步骤不能为空");
        }

        task.setId(UUID.randomUUID().toString())
                .setCreateTime(LocalDateTime.now())
                .setStatus(Task.STATUS_WAITING);

        task = taskRepository.insert(task);

        List<TaskStep> savedSteps = new ArrayList<>();
        for (TaskStep step : taskSteps) {
            step.setId(UUID.randomUUID().toString())
                    .setTaskId(task.getId())
                    .setStatus(Task.STATUS_WAITING)
                    .setCreateTime(LocalDateTime.now());
            TaskValidator.createTaskStep(step);
            savedSteps.add(taskStepRepository.insert(step));
        }

        return new TheTask(task, savedSteps);
    }

    @Override
    public Task updateTaskStatus(String id, String status) {
        if (!StringUtils.hasText(id)) {
            throw new UserException("任务 ID 不能为空");
        }
        TaskValidator.validateStatus(status);

        Task existing = taskRepository.selectById(id);
        if (existing == null) {
            throw new UserException("任务不存在: id=" + id);
        }

        LocalDateTime finishTime = TaskValidator.isTerminalStatus(status)
                ? LocalDateTime.now()
                : null;

        return taskRepository.updateStatus(id, status, finishTime);
    }

    @Override
    public Task finishTask(String id) {
        if (!StringUtils.hasText(id)) {
            throw new UserException("任务 ID 不能为空");
        }

        Task existing = taskRepository.selectById(id);
        if (existing == null) {
            throw new UserException("任务不存在: id=" + id);
        }

        return taskRepository.finishTask(id, LocalDateTime.now());
    }

    @Override
    @Transactional
    public Task deleteTask(String id) {
        if (!StringUtils.hasText(id)) {
            throw new UserException("任务 ID 不能为空");
        }

        Task deleted = taskRepository.deleteById(id);
        if (deleted == null) {
            throw new UserException("任务不存在: id=" + id);
        }
        taskStepRepository.deleteByTaskId(deleted.getId());

        log.info("删除任务: id={}, 已级联删除相关步骤", deleted.getId());
        return deleted;
    }

    @Override
    public TheTask selectTaskById(String id) {
        if (!StringUtils.hasText(id)) {
            throw new UserException("任务 ID 不能为空");
        }

        Task task = taskRepository.selectById(id);
        if (task == null) {
            return null;
        }

        List<TaskStep> steps = taskStepRepository.selectByTaskId(id);
        return new TheTask(task, steps);
    }

    @Override
    public TaskStep createTaskStep(TaskStep step) {
        TaskValidator.createTaskStep(step);

        // 校验父任务存在
        Task parent = taskRepository.selectById(step.getTaskId());
        if (parent == null) {
            throw new UserException("父任务不存在: taskId=" + step.getTaskId());
        }

        step.setId(UUID.randomUUID().toString())
                .setStatus(Task.STATUS_WAITING)
                .setCreateTime(LocalDateTime.now());

        return taskStepRepository.insert(step);
    }

    @Override
    public TaskStep updateTaskStepStatus(String id, String status) {
        if (!StringUtils.hasText(id)) {
            throw new UserException("任务步骤 ID 不能为空");
        }
        TaskValidator.validateStatus(status);

        TaskStep existing = taskStepRepository.selectById(id);
        if (existing == null) {
            throw new UserException("任务步骤不存在: id=" + id);
        }

        LocalDateTime finishTime = TaskValidator.isTerminalStatus(status)
                ? LocalDateTime.now()
                : null;

        return taskStepRepository.updateStatus(id, status, finishTime);
    }

    @Override
    public TaskStep finishStep(String id, String result) {
        if (!StringUtils.hasText(id)) {
            throw new UserException("任务步骤 ID 不能为空");
        }
        if (result == null) {
            throw new UserException("任务步骤结果不能为 null");
        }

        TaskStep existing = taskStepRepository.selectById(id);
        if (existing == null) {
            throw new UserException("任务步骤不存在: id=" + id);
        }

        return taskStepRepository.finishStep(id, result, LocalDateTime.now());
    }

    @Override
    @Transactional
    public TaskStep updateTaskStep(String stepId, String stepName, String stepDesc, Integer sort) {
        if (!StringUtils.hasText(stepId)) {
            throw new UserException("任务步骤 ID 不能为空");
        }

        TaskStep existing = taskStepRepository.selectById(stepId);
        if (existing == null) {
            throw new UserException("任务步骤不存在: id=" + stepId);
        }

        // 无排序变更：直接更新基本信息
        if (sort == null || sort.equals(existing.getSort())) {
            return taskStepRepository.updateStep(stepId, stepName, stepDesc,
                    sort != null ? sort : existing.getSort());
        }

        // 排序变更：需要重算同任务下所有步骤的排序
        List<TaskStep> allSteps = new ArrayList<>(taskStepRepository.selectByTaskId(existing.getTaskId()));

        // 从列表中移除当前步骤
        TaskStep target = null;
        for (Iterator<TaskStep> it = allSteps.iterator(); it.hasNext(); ) {
            TaskStep s = it.next();
            if (s.getId().equals(stepId)) {
                target = s;
                it.remove();
                break;
            }
        }
        if (target == null) {
            throw new UserException("任务步骤不存在: id=" + stepId);
        }

        // 更新目标步骤的字段
        if (StringUtils.hasText(stepName)) {
            target.setStepName(stepName);
        }
        if (StringUtils.hasText(stepDesc)) {
            target.setStepDesc(stepDesc);
        }

        // 计算插入位置（夹紧到有效范围）
        int insertPos = Math.max(0, Math.min(sort, allSteps.size()));
        allSteps.add(insertPos, target);

        // 重新编号：0, 1, 2, ...
        for (int i = 0; i < allSteps.size(); i++) {
            TaskStep s = allSteps.get(i);
            if (s.getSort() == null || s.getSort() != i) {
                taskStepRepository.updateSort(s.getId(), i);
            }
        }

        // 重新查询并返回更新后的步骤
        TaskStep updated = taskStepRepository.selectById(stepId);
        log.info("更新任务步骤: id={}, 排序已重算, 共{}个步骤", stepId, allSteps.size());
        return updated;
    }

    @Override
    public TaskListResult selectTasks(int limit, int offset) {
        if (limit <= 0) {
            throw new UserException("limit 必须大于 0");
        }
        if (offset < 0) {
            throw new UserException("offset 不能为负数");
        }

        List<Task> tasks = taskRepository.selectAll(limit, offset);
        int total = taskRepository.count();

        return new TaskListResult(tasks, total);
    }

    @Override
    public TaskStep deleteTaskStep(String id) {
        if (!StringUtils.hasText(id)) {
            throw new UserException("任务步骤 ID 不能为空");
        }

        TaskStep deleted = taskStepRepository.deleteById(id);
        if (deleted == null) {
            throw new UserException("任务步骤不存在: id=" + id);
        }

        return deleted;
    }
}
