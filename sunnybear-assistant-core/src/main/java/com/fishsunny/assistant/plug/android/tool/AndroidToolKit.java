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

    public AndroidToolKit(List<ToolHandler> tools,
                          @Value("${plug.android.tool.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
