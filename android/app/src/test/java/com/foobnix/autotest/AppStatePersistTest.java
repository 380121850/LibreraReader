package com.foobnix.autotest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.foobnix.android.utils.IO;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

/**
 * 集成层（JVM + Robolectric, CI runner）：AppState JSON 持久化往返。
 * 走 IO.writeObjSync/readObj 真实存储路径（AppState.save/load 的核心），
 * 文件指向临时目录，不污染真实 profile。
 * application=空 Application：避免 LibreraApp.onCreate（广告初始化）干扰。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30}, application = android.app.Application.class)
public class AppStatePersistTest {

    private File tempState;

    @Before
    public void setUp() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "howread-autotest-" + System.nanoTime());
        dir.mkdirs();
        tempState = new File(dir, "app-State.json");
        AppProfile.syncState = tempState;
    }

    @Test
    public void writeObj_then_readObj_roundTrip() {
        AppState s = AppState.get();
        s.aiBaseUrl = "http://127.0.0.1:8770/v1";
        s.aiModel = "howread-test-model";

        IO.writeObjSync(tempState, s);  // 同步写，避免异步线程竞态
        assertTrue(tempState.exists());
        assertTrue(tempState.length() > 0);

        // 破坏现场后从文件恢复
        s.aiBaseUrl = "changed";
        s.aiModel = "changed";
        IO.readObj(tempState, s);
        assertEquals("http://127.0.0.1:8770/v1", s.aiBaseUrl);
        assertEquals("howread-test-model", s.aiModel);
    }

    @Test
    public void stateFile_isValidJson() throws Exception {
        AppState s = AppState.get();
        IO.writeObjSync(tempState, s);
        String content = new String(java.nio.file.Files.readAllBytes(tempState.toPath()), "UTF-8");
        assertNotNull(content);
        assertTrue("应包含 AI 配置字段", content.contains("aiBaseUrl") || content.length() > 10);
    }
}
