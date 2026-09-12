package com.fishsunny.assistant.plug.android.tool;

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "plug.android.tool.enable", havingValue = "true", matchIfMissing = true)
public class AndroidToolKit extends ToolKit {

    public AndroidToolKit(List<ToolHandler> tools) {
        super(tools);
    }

    @Override
    public String displayName() {
        return "安卓设备";
    }

    @Override
    public String description() {
        return "操作安卓设备：点击、滑动、输入、按键、启动应用、截图与读取 UI 树";
    }
}
