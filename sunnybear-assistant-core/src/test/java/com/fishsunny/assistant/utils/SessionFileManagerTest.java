package com.fishsunny.assistant.utils;

/*
 * @Usage SessionFileManager 引用解析回归测试。
 *        重点是「可移植引用」与「历史文件系统路径」的判别：两者都以冒号开头形态出现
 *        （引用 "sessionId:fileName" vs 盘符 "E:\data\..."），判错会让存量附件全部解析失败。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/11
 */

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionFileManagerTest {

    /** 传 null 配置 → basePath 回落到 user.dir，不影响引用解析逻辑的验证 */
    private final SessionFileManager manager = new SessionFileManager(null);

    private static final String SESSION_ID = "1ec3a342-06ae-4c0e-b5a4-ccf42ff7d535";

    @Test
    void resolvesPortableRefIntoSessionDir() {
        Path resolved = manager.resolveRef(SESSION_ID + ":image_20260909.png");

        assertTrue(resolved.endsWith(Path.of("session", SESSION_ID, "file", "image_20260909.png")),
                "引用应解析到会话文件目录，实际: " + resolved);
    }

    // 以下两条取自 data/assistant.db 里真实存量消息的 url 字段，不是构造出来的样例
    private static final String LEGACY_ABS =
            "E:\\project\\sunnybear-assistant\\sunnybear-assistant-core\\data\\session\\"
                    + "1ec3a342-6c65-4ae1-8b85-666de87cebb7\\file\\image_20260909_194103724.png";
    private static final String LEGACY_REL =
            "data\\session\\a8d1def1-19cf-4c5a-9bfd-83799e0585d1\\file\\0_image.png";

    @Test
    void windowsDrivePathIsNotMistakenForRef() {
        // "E:" 与引用前缀同形，必须靠「: 之后含路径分隔符」挡掉，否则会解析成 session "E"
        Path resolved = manager.resolveRef(LEGACY_ABS);

        assertEquals(Path.of(LEGACY_ABS), resolved, "历史盘符路径必须原样处理，实际: " + resolved);
    }

    @Test
    void relativeLegacyPathIsPassedThrough() {
        assertEquals(Path.of(LEGACY_REL), manager.resolveRef(LEGACY_REL));
    }

    @Test
    void forwardSlashDrivePathIsNotMistakenForRef() {
        // 部分历史数据写入的是正斜杠形态
        String legacy = "E:/project/sunnybear-assistant/data/session/abc/file/x.png";

        assertEquals(Path.of(legacy), manager.resolveRef(legacy));
    }

    @Test
    void readSessionFileReturnsNullForMissingFileInsteadOfMisresolving() {
        // 路径带盘符前缀（tmpdir 在 Windows 上形如 C:\Users\...）且文件确定不存在：
        // 必须老实返回 null，不能因前缀误判去 session/C/... 找一个不存在的目录
        String missing = Path.of(System.getProperty("java.io.tmpdir"),
                "sunnybear-no-such-session", "file", "nope.png").toString();

        assertNull(assertDoesNotThrow(() -> manager.readSessionFile(missing)));
    }

    @Test
    void buildRefRoundTripsThroughResolve() {
        String ref = manager.buildRef(SESSION_ID, "command_20260911.log");

        assertEquals(SESSION_ID + ":command_20260911.log", ref);
        assertTrue(manager.resolveRef(ref).endsWith(Path.of("session", SESSION_ID, "file", "command_20260911.log")));
    }

    @Test
    void sessionDirLayoutIsStable() {
        Path dir = manager.buildSessionDirPath(SESSION_ID);

        assertTrue(dir.endsWith(Path.of("session", SESSION_ID, "file")), "实际: " + dir);
        // deleteSessionDir 删的是 file 的父目录，即整个会话目录
        assertEquals(dir.getParent(), manager.buildSessionRootPath(SESSION_ID));
    }
}
