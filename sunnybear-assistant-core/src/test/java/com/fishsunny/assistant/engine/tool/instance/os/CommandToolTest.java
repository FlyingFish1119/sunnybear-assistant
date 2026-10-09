package com.fishsunny.assistant.engine.tool.instance.os;

/*
 * @Usage CommandTool（Windows PowerShell）执行与退出码测试。
 *        Windows PowerShell 的退出码语义不同于 cmd：原生程序失败只回 1，且会被后续成功命令掩盖。
 *        这里锁定退出码守卫（真实退出码透传、失败不被掩盖）以及 PowerShell 语义的白/黑名单与 ; 拆分。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/9 10:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.utils.SessionFileManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class CommandToolTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** NEVER_ASKED 不会触碰 SecurityService，故传 null 即可 */
    private CommandTool newTool(String mode) {
        CommandTool.Settings settings = new CommandTool.Settings(mode);
        return new CommandTool(OBJECT_MAPPER, settings, null, new SessionFileManager(null, null), null);
    }

    private String run(CommandTool tool, String command) throws Exception {
        String args = OBJECT_MAPPER.writeValueAsString(Map.of("command", command));
        return tool.action(args, Map.of()).getResult();
    }

    @Test
    void successfulCommandReturnsOutput() throws Exception {
        CommandTool tool = newTool(CommandTool.NEVER_ASKED);
        assertTrue(run(tool, "Write-Output sunnybear-ok").contains("sunnybear-ok"));
    }

    @Test
    void nativeNonZeroExitCodeIsPreserved() throws Exception {
        // 修复前：powershell 对 cmd /c exit 7 只回 1，真实退出码 7 丢失
        CommandTool tool = newTool(CommandTool.NEVER_ASKED);
        ToolExecutor.ToolExecuteException ex = assertThrows(ToolExecutor.ToolExecuteException.class,
                () -> run(tool, "cmd /c exit 7"));
        assertTrue(ex.getMessage().contains("退出码：7"), ex.getMessage());
    }

    @Test
    void cmdletErrorSurfacesAsFailure() throws Exception {
        // 内置命令报非终止性错误：$LASTEXITCODE 未设置，但 $? 为假，应以非零收场
        CommandTool tool = newTool(CommandTool.NEVER_ASKED);
        ToolExecutor.ToolExecuteException ex = assertThrows(ToolExecutor.ToolExecuteException.class,
                () -> run(tool, "Get-Item C:\\nonexistent_sunnybear_xyz"));
        assertTrue(ex.getMessage().contains("退出码：1"), ex.getMessage());
    }

    @Test
    void earlierFailureNotMaskedByLaterSuccess() throws Exception {
        // ; 分隔：前面的原生命令失败，后面的成功命令不能把退出码冲成 0
        CommandTool tool = newTool(CommandTool.NEVER_ASKED);
        ToolExecutor.ToolExecuteException ex = assertThrows(ToolExecutor.ToolExecuteException.class,
                () -> run(tool, "cmd /c exit 5; Write-Output done"));
        assertTrue(ex.getMessage().contains("退出码：5"), ex.getMessage());
    }

    @Test
    void whitelistedPowerShellCommandRunsWithoutReview() throws Exception {
        // AUTO 模式白名单命中应跳过审查；SecurityService 传 null，若误走审查会 NPE
        CommandTool tool = newTool(CommandTool.AUTO);
        assertTrue(run(tool, "Write-Output whitelisted-ok").contains("whitelisted-ok"));
    }

    @Test
    void powershellBlacklistRejectsRecursiveDelete() throws Exception {
        // ALWAYS_REJECT_DANGER：PowerShell 语义的危险命令应被拒绝，且不真正执行
        CommandTool tool = newTool(CommandTool.ALWAYS_REJECT_DANGER);
        ToolExecutor.ToolExecuteException ex = assertThrows(ToolExecutor.ToolExecuteException.class,
                () -> run(tool, "Remove-Item -Recurse -Force C:\\nonexistent_sunnybear_dir"));
        assertTrue(ex.getMessage().contains("拒绝执行"), ex.getMessage());
    }

    @Test
    void semicolonSplitsCommandsSoHiddenDangerIsCaught() throws Exception {
        // 前面的安全命令不能用 ; 把后面的危险命令（含别名）夹带过去
        CommandTool tool = newTool(CommandTool.ALWAYS_REJECT_DANGER);
        ToolExecutor.ToolExecuteException ex = assertThrows(ToolExecutor.ToolExecuteException.class,
                () -> run(tool, "Write-Output ok; rm -rf C:\\nonexistent_sunnybear_dir"));
        assertTrue(ex.getMessage().contains("拒绝执行"), ex.getMessage());
    }
}
