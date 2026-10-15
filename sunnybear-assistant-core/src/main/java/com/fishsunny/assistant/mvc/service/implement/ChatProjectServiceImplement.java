package com.fishsunny.assistant.mvc.service.implement;

/*
 * @Usage 项目服务实现。
 *        会话归属的读写一律经 ChatSessionService.mergeExtension —— 它是 extension 的唯一写入口，
 *        只覆盖传入的键，不会抹掉 token 统计、标记清单等其它模块写进去的东西。
 *        删除项目 = 解绑会话 + 删记录 + 清 {basePath}/project/{projectId} 目录（资料跟着项目走）。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/15
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatProject;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.mvc.dao.ChatProjectRepository;
import com.fishsunny.assistant.mvc.service.ChatProjectBindings;
import com.fishsunny.assistant.mvc.service.ChatProjectService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.utils.ProjectFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ChatProjectServiceImplement implements ChatProjectService {

    private static final Logger log = LoggerFactory.getLogger(ChatProjectServiceImplement.class);

    /**
     * 新建项目自动分配的颜色。低饱和的一小套，跟暖色主题搭得住；
     * 换色时前端用的也是这同一套（见 static/components/nav-rail/project-panel）。
     */
    private static final String[] PALETTE = {
            "#E1785C", "#D9A441", "#7FA86B", "#5B93C7",
            "#9184C9", "#C97BA6", "#4FA39B", "#A8805B"
    };

    private final ChatProjectRepository chatProjectRepository;
    private final ChatSessionService chatSessionService;
    private final ProjectFileManager projectFileManager;

    @Autowired
    public ChatProjectServiceImplement(ChatProjectRepository chatProjectRepository,
                                       ChatSessionService chatSessionService,
                                       ProjectFileManager projectFileManager) {
        this.chatProjectRepository = chatProjectRepository;
        this.chatSessionService = chatSessionService;
        this.projectFileManager = projectFileManager;
    }

    @Override
    public List<ChatProject> listAll() {
        List<ChatProject> projects = chatProjectRepository.selectAll();
        // 顺带把会话数填上，供项目行显示「N 个会话」。
        // 没有走 count 聚合 SQL：项目数量是个人量级（几个到几十个），逐个查一遍最直白。
        for (ChatProject project : projects) {
            project.setSessionCount(listSessions(project.getId()).size());
        }
        return projects;
    }

    @Override
    public ChatProject findById(String id) {
        return StringUtils.hasText(id) ? chatProjectRepository.selectById(id) : null;
    }

    @Override
    public ChatProject create(String name, String description, String color) {
        String trimmed = name == null ? "" : name.trim();
        if (!StringUtils.hasText(trimmed)) {
            throw new IllegalArgumentException("项目名不能为空");
        }
        if (chatProjectRepository.selectByName(trimmed) != null) {
            throw new IllegalArgumentException("已经有一个叫「" + trimmed + "」的项目了");
        }
        ChatProject project = new ChatProject(trimmed);
        project.setDescription(description == null ? "" : description.trim());
        project.setColor(StringUtils.hasText(color) ? color.trim() : nextColor());
        project.setId(UUID.randomUUID().toString())
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        // 新项目开局就带一份 PROJECT.md 进自动追加：之后建的会话一开口就拿着它当背景
        project.setAutoAppendFiles(new ArrayList<>(List.of(ChatProjectBindings.PROJECT_FILE_NAME)));
        ChatProject saved = chatProjectRepository.insert(project);
        // 每个项目自带一份 PROJECT.md 作为资料入口；同名直接覆盖，不做唯一名绕行。
        // 生成失败不拦项目创建，记条日志就行（面板里也能自己再建一份）。
        try {
            projectFileManager.writeProjectText(saved.getId(), ChatProjectBindings.PROJECT_FILE_NAME, buildProjectReadme(saved));
        } catch (Exception e) {
            log.warn("生成项目文档失败 projectId={}: {}", saved.getId(), e.getMessage());
        }
        return saved;
    }

    /** 项目入口文档的初始内容：名字与描述打底，往下写什么交给用户 */
    private String buildProjectReadme(ChatProject project) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(project.getName()).append("\n\n");
        if (StringUtils.hasText(project.getDescription())) {
            sb.append(project.getDescription()).append("\n\n");
        }
        sb.append("> 这是本项目的资料入口文档，存在 data/project/").append(project.getId()).append("/ 下。\n");
        sb.append("> 同目录的其它文本文件，可以在导航轨「项目」面板里直接新建、编辑、删除。\n");
        return sb.toString();
    }

    @Override
    public ChatProject update(String id, String name, String description, String color) {
        ChatProject current = chatProjectRepository.selectById(id);
        if (current == null) {
            throw new IllegalArgumentException("项目不存在：" + id);
        }
        String trimmed = name == null ? "" : name.trim();
        if (!StringUtils.hasText(trimmed)) {
            throw new IllegalArgumentException("项目名不能为空");
        }
        ChatProject sameName = chatProjectRepository.selectByName(trimmed);
        if (sameName != null && !sameName.getId().equals(id)) {
            throw new IllegalArgumentException("已经有一个叫「" + trimmed + "」的项目了");
        }
        current.setName(trimmed);
        // null = 这一项不动；空串 = 清空（前端编辑弹窗每次都传全量，这里只是兜底）
        if (description != null) {
            current.setDescription(description.trim());
        }
        if (color != null) {
            current.setColor(color.trim());
        }
        current.setUpdateTime(LocalDateTime.now());
        return chatProjectRepository.update(current);
    }

    @Override
    public List<String> listAutoAppendPaths(String projectId) {
        ChatProject project = findById(projectId);
        if (project == null || CollectionUtils.isEmpty(project.getAutoAppendFiles())) {
            return List.of();
        }
        List<String> paths = new ArrayList<>(project.getAutoAppendFiles().size());
        for (String fileName : project.getAutoAppendFiles()) {
            Path file = projectFileManager.resolveExistingProjectFile(projectId, fileName);
            if (file != null) {
                paths.add(file.toString());
            }
        }
        return paths;
    }

    @Override
    public ChatProject updateAutoAppendFile(String projectId, String fileName, boolean auto) {
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        ChatProject current = chatProjectRepository.selectById(projectId);
        if (current == null) {
            throw new IllegalArgumentException("项目不存在：" + projectId);
        }
        List<String> files = new ArrayList<>(current.getAutoAppendFiles());
        // 幂等：已经是要的样子就原样返回，既不白写一次库，也不动 update_time
        boolean changed = auto ? files.add(fileName) : files.remove(fileName);
        if (!changed) {
            return current;
        }
        current.setAutoAppendFiles(files);
        return chatProjectRepository.update(current);
    }

    @Override
    public void delete(String id) {
        ChatProject project = chatProjectRepository.selectById(id);
        if (project == null) {
            return;
        }
        // 先解绑：删项目不该顺手把主人的聊天记录带走，会话原地退回「散会话」
        List<ChatSession> bound = listSessions(id);
        for (ChatSession session : bound) {
            try {
                chatSessionService.mergeExtension(session.getId(),
                        Map.of(ChatProjectBindings.EXTENSION_KEY, ChatProjectBindings.UNBOUND));
            } catch (Exception e) {
                // 单个解绑失败不阻断删除，记一条日志留痕
                log.warn("解绑会话失败 sessionId={}, projectId={}: {}", session.getId(), id, e.getMessage());
            }
        }
        chatProjectRepository.deleteById(id);
        // 记录删掉之后才清目录：删库失败时目录还在，资料不会「库没了、文件也没了」；
        // 清目录本身失败只记日志（deleteProjectDir 内部已兜），不回滚这次删除。
        projectFileManager.deleteProjectDir(id);
        log.info("项目已删除并解绑 {} 个会话：{}", bound.size(), project.getName());
    }

    @Override
    public ChatSession attachSession(String sessionId, String projectId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        if (!StringUtils.hasText(projectId)) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
        ChatSession session = chatSessionService.findById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在：" + sessionId);
        }
        if (chatProjectRepository.selectById(projectId) == null) {
            throw new IllegalArgumentException("项目不存在：" + projectId);
        }
        // 值必须写成字符串：按 extension 反查会话走 json_extract，数字型会静默比较不上
        Map<String, Object> merged = chatSessionService.mergeExtension(sessionId,
                Map.of(ChatProjectBindings.EXTENSION_KEY, projectId));
        session.setExtension(merged);
        return session;
    }

    @Override
    public ChatSession detachSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        ChatSession session = chatSessionService.findById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在：" + sessionId);
        }
        Map<String, Object> merged = chatSessionService.mergeExtension(sessionId,
                Map.of(ChatProjectBindings.EXTENSION_KEY, ChatProjectBindings.UNBOUND));
        session.setExtension(merged);
        return session;
    }

    @Override
    public List<ChatSession> listSessions(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return List.of();
        }
        return chatSessionService.findByTypeAndExtensionValue(
                ChatProjectBindings.SESSION_TYPE, ChatProjectBindings.EXTENSION_KEY, projectId);
    }

    /** 取一个当前还没被项目占用的颜色；全用完了就按项目数量轮取一个 */
    private String nextColor() {
        Set<String> used = new HashSet<>();
        for (ChatProject project : chatProjectRepository.selectAll()) {
            if (StringUtils.hasText(project.getColor())) {
                used.add(project.getColor());
            }
        }
        for (String color : PALETTE) {
            if (!used.contains(color)) {
                return color;
            }
        }
        return PALETTE[used.size() % PALETTE.length];
    }
}
