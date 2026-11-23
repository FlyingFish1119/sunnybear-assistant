package com.fishsunny.assistant.plug.comfyui.service;

/*
 * @Usage ComfyUI 核心服务：把「查资源 / 工作流读写 / 生图」翻译成 Python 脚本，经卫星机 exec 执行。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/23
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.instance.OSToolKit;
import com.fishsunny.assistant.engine.tool.service.background.CommandTaskRegistry;
import com.fishsunny.assistant.engine.tool.service.satellite.dto.ExecResult;
import com.fishsunny.assistant.engine.tool.service.satellite.service.SatelliteBridgeService;
import com.fishsunny.assistant.plug.comfyui.config.ComfyUIConfig;
import com.fishsunny.assistant.utils.SessionFileManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ComfyUI 核心服务。
 * <p>
 * 不再需要专用代理进程：ComfyUI 本身就装在那台卫星机上，核心端把要做的 HTTP 调用写成一段
 * Python 脚本，经 {@code exec} 的 {@code mode=script} 下发执行，脚本把结果以紧凑 JSON 打到
 * stdout，卫星端原样回传。脚本正文走 WebSocket 文本帧，没有命令行长度限制与 shell 转义问题。
 * </p>
 * <p>
 * 三个操作：
 * <ul>
 *   <li>resources —— 扫 models 目录拿模型清单，/object_info 只取采样器与调度器；</li>
 *   <li>workflow —— 直接读工作流目录，list 给元信息、detail 给完整 JSON；</li>
 *   <li>generate —— POST /prompt → 轮询 /queue → GET /history/{id}，与老代理同一套流程。</li>
 * </ul>
 * 生成图由本类经卫星下载通道拉回会话文件，不再走 base64 回传。
 * </p>
 */
@Slf4j
@Component
public class ComfyUIService {

    /** 单次工具输出的软上限：超过就把内容落到会话日志并只回引用 */
    private static final int MAX_INLINE_OUTPUT = 60000;

    private final ComfyUIConfig config;
    private final SatelliteBridgeService bridgeService;
    private final SessionFileManager sessionFileManager;
    private final ObjectMapper objectMapper;

    public ComfyUIService(ComfyUIConfig config,
                          SatelliteBridgeService bridgeService,
                          SessionFileManager sessionFileManager,
                          ObjectMapper objectMapper) {
        this.config = config;
        this.bridgeService = bridgeService;
        this.sessionFileManager = sessionFileManager;
        this.objectMapper = objectMapper;
    }

    // ==================== 对外操作 ====================

    /** 查询可用资源：采样器、调度器，以及 models 目录下的模型文件清单 */
    public String resources(String sessionId) throws ToolExecutor.ToolExecuteException {
        String script = RESOURCES_SCRIPT
                .replace("__BASE_URL__", escapePython(baseUrl()))
                .replace("__MODELS_B64__", b64(modelsDir()));
        return runScript(script, "ComfyUI 资源查询", sessionId, 180);
    }

    /** 列出 API 格式工作流 */
    public String listWorkflows(String sessionId) throws ToolExecutor.ToolExecuteException {
        String script = WORKFLOW_SCRIPT
                .replace("__ACTION__", "list")
                .replace("__DIR_B64__", b64(workflowDir()))
                .replace("__NAME_B64__", "");
        return runScript(script, "ComfyUI 工作流列表", sessionId, 120);
    }

    /** 读取指定工作流的完整 JSON */
    public String workflowDetail(String workflowName, String sessionId) throws ToolExecutor.ToolExecuteException {
        if (!StringUtils.hasText(workflowName)) {
            throw new ToolExecutor.ToolExecuteException("参数 workflowName 不能为空");
        }
        String script = WORKFLOW_SCRIPT
                .replace("__ACTION__", "detail")
                .replace("__DIR_B64__", b64(workflowDir()))
                .replace("__NAME_B64__", b64(workflowName.trim()));
        return runScript(script, "ComfyUI 工作流详情: " + workflowName, sessionId, 120);
    }

    /**
     * 提交 workflow 生图，阻塞到完成或超时。
     * <p>
     * 返回卫星脚本的原始 JSON（promptId / elapsed / images），随后把图片经下载通道拉回会话目录，
     * 并在 JSON 里补一个 {@code files} 字段（会话内可移植引用），供上层直接读取。
     * </p>
     */
    public String generate(String workflowJson, Integer timeoutSec, String sessionId)
            throws ToolExecutor.ToolExecuteException {
        if (!StringUtils.hasText(workflowJson)) {
            throw new ToolExecutor.ToolExecuteException("参数 workflow 不能为空");
        }
        // 先确认是合法 JSON，避免把脏数据 base64 塞进脚本后到卫星端才炸
        try {
            objectMapper.readTree(workflowJson);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数 workflow 不是合法 JSON: " + e.getMessage());
        }

        int timeout = timeoutSec != null && timeoutSec > 0 ? timeoutSec : config.getGenerateTimeoutS();
        String script = GENERATE_SCRIPT
                .replace("__BASE_URL__", escapePython(baseUrl()))
                .replace("__TIMEOUT__", String.valueOf(timeout))
                .replace("__WF_B64__", b64(workflowJson));

        String result = runScript(script, "ComfyUI 生图", sessionId, timeout + 120);

        JsonNode node;
        try {
            node = objectMapper.readTree(result);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("解析生图结果失败: " + result);
        }
        if (node.hasNonNull("error")) {
            throw new ToolExecutor.ToolExecuteException("ComfyUI 生图失败: " + node.get("error").asText());
        }

        // 图片拉回本机（失败不掩盖生图成功，只记录）
        List<String> files = downloadImages(node, sessionId);
        if (!files.isEmpty()) {
            ((ObjectNode) node).putPOJO("files", files);
        }
        return node.toString();
    }

    // ==================== 图片下载 ====================

    /**
     * 按脚本返回的 images 列表，把 output 目录下的文件逐个拉回会话文件目录。
     * 返回会话内相对路径（可移植引用）。
     */
    private List<String> downloadImages(JsonNode node, String sessionId) {
        List<String> files = new ArrayList<>();
        if (sessionId == null || !node.has("images")) {
            return files;
        }
        for (JsonNode image : node.get("images")) {
            String filename = image.path("filename").asText("");
            String subfolder = image.path("subfolder").asText("");
            String type = image.path("type").asText("output");
            if (filename.isEmpty() || !"output".equalsIgnoreCase(type)) {
                continue;
            }
            try {
                Path remote = Paths.get(config.getOutputDir(), subfolder, filename);
                Path savePath = sessionFileManager.buildSessionDirPath(sessionId).resolve(filename);
                if (savePath.getParent() != null) {
                    Files.createDirectories(savePath.getParent());
                }
                bridgeService.download(config.getDevice(), remote.toString(), savePath);
                // 真实引用（{会话ID}:{文件名}）：/file/proxy 与 readSessionFile 都按它解析，
                // 不能用 buildSessionMarker 的字面量前缀，否则前端会去 session/sessionId/ 下找文件
                files.add(sessionFileManager.buildRef(sessionId, filename));
                log.info("ComfyUI 生成图已拉回: {}", savePath);
            } catch (Exception e) {
                log.warn("生成图拉回失败 [{}]: {}", filename, e.getMessage());
            }
        }
        return files;
    }

    // ==================== 脚本执行 ====================

    /**
     * 下发一段脚本到卫星机执行，返回 stdout。
     * <p>同步等待：脚本自己在内部处理超时；若卫星端等待窗口仍到期，按失败处理并给出日志位置。</p>
     */
    private String runScript(String script, String title, String sessionId, int waitTimeoutSec)
            throws ToolExecutor.ToolExecuteException {
        if (!StringUtils.hasText(config.getDevice())) {
            throw new ToolExecutor.ToolExecuteException("未配置 ComfyUI 卫星机（plug.comfyui.device）");
        }
        if (sessionId == null) {
            throw new ToolExecutor.ToolExecuteException("ComfyUI 操作需要会话上下文");
        }
        String taskId = SatelliteBridgeService.newTaskId();
        String logRelPath = OSToolKit.buildLogRelativePath("bridge_comfyui_" + taskId + ".log");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskId", taskId);
        params.put("mode", "script");
        params.put("scriptType", "python");
        params.put("content", script);
        params.put("waitTimeoutSec", waitTimeoutSec);

        ExecResult result = bridgeService.sendExecCommand(
                config.getDevice(), taskId, sessionId,
                CommandTaskRegistry.TaskType.BRIDGE_SCRIPT, title, logRelPath, false, params);

        if (!Boolean.TRUE.equals(result.getFinished())) {
            throw new ToolExecutor.ToolExecuteException(
                    title + "在卫星机执行超过 " + waitTimeoutSec + " 秒，已转后台。日志文件: "
                            + SessionFileManager.buildSessionMarker(logRelPath));
        }
        Integer exitCode = result.getExitCode();
        String output = result.getOutput() == null ? "" : result.getOutput().trim();
        if (exitCode == null || exitCode != 0) {
            throw new ToolExecutor.ToolExecuteException(
                    title + "失败，退出码 " + exitCode + "，输出: " + abbreviate(output));
        }
        if (output.isEmpty()) {
            throw new ToolExecutor.ToolExecuteException(title + "没有返回任何内容");
        }
        if (output.length() > MAX_INLINE_OUTPUT) {
            try {
                String marker = sessionFileManager.writeSessionFile(
                        sessionId, "comfyui_" + taskId + ".json", output.getBytes(StandardCharsets.UTF_8));
                return title + "结果过大（" + output.length() + " 字符），已保存到会话文件: " + marker
                        + "。请用 file_read_tool 分段读取该文件。";
            } catch (Exception e) {
                throw new ToolExecutor.ToolExecuteException(
                        title + "结果过大（" + output.length() + " 字符）且落盘失败: " + e.getMessage());
            }
        }
        return output;
    }

    private String baseUrl() {
        String url = config.getBaseUrl();
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("未配置 ComfyUI 地址（plug.comfyui.base-url）");
        }
        return url.replaceAll("/+$", "");
    }

    private String workflowDir() {
        if (!StringUtils.hasText(config.getWorkflowDir())) {
            throw new IllegalStateException("未配置工作流目录（plug.comfyui.workflow-dir）");
        }
        return config.getWorkflowDir();
    }

    private String modelsDir() {
        if (!StringUtils.hasText(config.getModelsDir())) {
            throw new IllegalStateException("未配置模型目录（plug.comfyui.models-dir）");
        }
        return config.getModelsDir();
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 简单转义：脚本里的字符串字面量只用来拼 URL，避免引号把脚本截断 */
    private static String escapePython(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String abbreviate(String text) {
        return text.length() <= 800 ? text : text.substring(0, 800) + "...";
    }

    // ==================== Python 脚本模板 ====================

    /**
     * 资源查询：
     * <ul>
     *   <li>models —— 直接扫 models 目录，每个一级子目录作为类别，递归列出模型文件（相对路径，
     *       与 workflow 里填写的模型名一致）；</li>
     *   <li>samplers / schedulers —— 只从 /object_info 的 KSampler 节点取这两个枚举；
     *       KSampler 不存在时退而在所有节点里找同名字段。</li>
     * </ul>
     * 不再解析全部节点定义——API workflow 本身就是节点结构的参照。
     */
    private static final String RESOURCES_SCRIPT = """
            import json, os, sys, base64, urllib.request

            sys.stdout.reconfigure(encoding='utf-8')
            BASE = "__BASE_URL__"
            MODELS_DIR = base64.b64decode("__MODELS_B64__").decode('utf-8')
            EXTENSIONS = ('.safetensors', '.ckpt', '.pt', '.pth', '.bin', '.onnx', '.sft', '.gguf')

            def emit(obj):
                print(json.dumps(obj, ensure_ascii=False, separators=(',', ':')))
                sys.exit(0)

            def http_get(path):
                with urllib.request.urlopen(BASE + path, timeout=120) as resp:
                    return json.loads(resp.read().decode('utf-8'))

            def options_from_required(required, field):
                val = required.get(field)
                if not val:
                    return []
                first = val[0]
                if isinstance(first, list) and all(isinstance(x, str) for x in first):
                    return first
                return []

            def options_of(nodes, node_name, field):
                node = nodes.get(node_name) or {}
                required = (node.get('input') or {}).get('required') or {}
                return options_from_required(required, field)

            if not os.path.isdir(MODELS_DIR):
                emit({'error': '模型目录不存在: ' + MODELS_DIR})

            models = {}
            for category in sorted(os.listdir(MODELS_DIR)):
                category_path = os.path.join(MODELS_DIR, category)
                if not os.path.isdir(category_path):
                    continue
                files = []
                for root, _, names in os.walk(category_path):
                    for name in names:
                        if not name.lower().endswith(EXTENSIONS):
                            continue
                        full = os.path.join(root, name)
                        files.append(os.path.relpath(full, category_path))
                if files:
                    models[category] = sorted(files, key=lambda s: s.lower())

            samplers = []
            schedulers = []
            try:
                nodes = http_get('/object_info')
                samplers = options_of(nodes, 'KSampler', 'sampler_name')
                schedulers = options_of(nodes, 'KSampler', 'scheduler')
                if not samplers or not schedulers:
                    for node in nodes.values():
                        required = ((node or {}).get('input') or {}).get('required') or {}
                        if not samplers and 'sampler_name' in required:
                            samplers = options_from_required(required, 'sampler_name')
                        if not schedulers and 'scheduler' in required:
                            schedulers = options_from_required(required, 'scheduler')
                        if samplers and schedulers:
                            break
            except Exception as e:
                emit({'error': '读取 /object_info 失败: ' + str(e)})

            emit({'samplers': samplers, 'schedulers': schedulers, 'models': models})
            """;

    /** 工作流读写：action=list 列文件元信息，action=detail 读完整 JSON */
    private static final String WORKFLOW_SCRIPT = """
            import json, os, sys, base64, time

            sys.stdout.reconfigure(encoding='utf-8')
            ACTION = "__ACTION__"
            DIR = base64.b64decode("__DIR_B64__").decode('utf-8')
            NAME = base64.b64decode("__NAME_B64__").decode('utf-8') if ACTION == 'detail' else ''

            def emit(obj):
                print(json.dumps(obj, ensure_ascii=False, separators=(',', ':')))
                sys.exit(0)

            if not os.path.isdir(DIR):
                emit({'error': '工作流目录不存在: ' + DIR})

            if ACTION == 'list':
                items = []
                for name in sorted(os.listdir(DIR)):
                    if not name.lower().endswith('.json'):
                        continue
                    path = os.path.join(DIR, name)
                    if not os.path.isfile(path):
                        continue
                    info = {
                        'name': name,
                        'size': os.path.getsize(path),
                        'lastModified': time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(os.path.getmtime(path))),
                    }
                    try:
                        with open(path, 'r', encoding='utf-8') as f:
                            workflow = json.load(f)
                        info['nodeCount'] = len(workflow)
                        info['nodeTypes'] = [v.get('class_type') for v in workflow.values()
                                             if isinstance(v, dict) and v.get('class_type')]
                    except Exception as e:
                        info['parseError'] = str(e)
                    items.append(info)
                emit({'path': DIR, 'count': len(items), 'workflows': items})

            root = os.path.abspath(DIR)
            target = os.path.abspath(os.path.join(DIR, NAME))
            if not target.startswith(root + os.sep):
                emit({'error': '非法的文件名: ' + NAME})
            if not os.path.isfile(target):
                emit({'error': '工作流文件不存在: ' + target})
            try:
                with open(target, 'r', encoding='utf-8') as f:
                    workflow = json.load(f)
            except Exception as e:
                emit({'error': '工作流 JSON 解析失败: ' + str(e)})
            emit({'name': NAME, 'path': target, 'size': os.path.getsize(target), 'workflow': workflow})
            """;

    /** 生图：提交 → 轮询队列 → 取 history，输出 promptId / elapsed / images */
    private static final String GENERATE_SCRIPT = """
            import base64, json, sys, time, urllib.request, urllib.error

            sys.stdout.reconfigure(encoding='utf-8')
            BASE = "__BASE_URL__"
            TIMEOUT = __TIMEOUT__
            WORKFLOW = json.loads(base64.b64decode("__WF_B64__").decode('utf-8'))

            BS = chr(92)

            def emit(obj):
                print(json.dumps(obj, ensure_ascii=False, separators=(',', ':')))
                sys.exit(0)

            def http_get(path):
                with urllib.request.urlopen(BASE + path, timeout=120) as resp:
                    return json.loads(resp.read().decode('utf-8'))

            def normalize_paths(workflow):
                # 路径字段兜底：AI 写单斜杠 / 正斜杠 / 多余反斜杠都能纠正到 ComfyUI 的真实候选值
                try:
                    nodes = http_get('/object_info')
                except Exception:
                    return
                for node in workflow.values():
                    if not isinstance(node, dict):
                        continue
                    inputs = node.get('inputs') or {}
                    info = nodes.get(node.get('class_type')) or {}
                    required = (info.get('input') or {}).get('required') or {}
                    for field, value in list(inputs.items()):
                        if not isinstance(value, str):
                            continue
                        candidates = required.get(field)
                        if not candidates or not isinstance(candidates[0], list):
                            continue
                        options = candidates[0]
                        if not options or not isinstance(options[0], str):
                            continue
                        if value in options:
                            continue
                        key = value.replace(BS, '').replace('/', '').lower()
                        matches = [o for o in options
                                   if o.replace(BS, '').replace('/', '').lower() == key]
                        if len(matches) == 1:
                            inputs[field] = matches[0]

            normalize_paths(WORKFLOW)

            def http_post(path, body):
                data = json.dumps(body).encode('utf-8')
                req = urllib.request.Request(BASE + path, data=data, headers={'Content-Type': 'application/json'})
                with urllib.request.urlopen(req, timeout=300) as resp:
                    return json.loads(resp.read().decode('utf-8'))

            try:
                submitted = http_post('/prompt', {'prompt': WORKFLOW})
            except urllib.error.HTTPError as e:
                detail = e.read().decode('utf-8', 'replace')[:800]
                emit({'error': '提交失败 HTTP %s: %s' % (e.code, detail)})
            except Exception as e:
                emit({'error': '提交失败: %s' % str(e)})

            pid = submitted.get('prompt_id')
            if not pid:
                emit({'error': '提交失败，未返回 prompt_id: ' + str(submitted)[:800]})

            start = time.time()
            while True:
                if time.time() - start > TIMEOUT:
                    emit({'error': '生成超时（%ss）' % TIMEOUT, 'promptId': pid})
                try:
                    queue = http_get('/queue')
                except Exception as e:
                    emit({'error': '查询队列失败: %s' % str(e), 'promptId': pid})
                running = queue.get('queue_running') or []
                pending = queue.get('queue_pending') or []
                ids = [entry[1] for entry in running + pending
                       if isinstance(entry, list) and len(entry) > 1]
                if pid not in ids:
                    break
                time.sleep(2)

            entry = None
            for _ in range(10):
                try:
                    history = http_get('/history/' + pid)
                except Exception:
                    history = {}
                if pid in history:
                    entry = history[pid]
                    break
                time.sleep(2)
            if entry is None:
                emit({'error': '未找到生成结果', 'promptId': pid})

            status = entry.get('status') or {}
            if status.get('status_str') == 'error':
                emit({'error': '生成失败: ' + str(status.get('messages'))[:800], 'promptId': pid})

            images = []
            for node in (entry.get('outputs') or {}).values():
                for img in (node.get('images') or []):
                    if img.get('filename'):
                        images.append({
                            'filename': img.get('filename'),
                            'subfolder': img.get('subfolder') or '',
                            'type': img.get('type') or 'output',
                        })

            emit({'promptId': pid, 'elapsed': round(time.time() - start, 1), 'images': images})
            """;
}
