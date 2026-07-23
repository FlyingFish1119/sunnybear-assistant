package com.fishsunny.assistant.engine.protocol.standard.chat.tools.register;

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Data
@Accessors(chain = true)
public class StandardToolRegister {

    private final String type = "function";

    private StandardToolRegisterFunction function;

    public StandardToolRegister() {
    }

    private static final Function<ToolRegister, StandardToolRegister> TOOL_REGISTER_CONVERTER = register -> {
        Map<String, StandardToolRegisterProperty> properties = new HashMap<>();
        for (ToolRegister.Parameters parameter : register.getParameters()) {
            StandardToolRegisterProperty property = new StandardToolRegisterProperty()
                    .setType(parameter.getType())
                    .setDescription(parameter.getDescription());
            properties.put(parameter.getParameterName(), property);
        }
        StandardToolRegisterParameter parameter = new StandardToolRegisterParameter()
                .setProperties(properties).setRequired(register.getRequired());
        return new StandardToolRegister()
                .setFunction(new StandardToolRegisterFunction()
                        .setName(register.getName())
                        .setDescription(register.getDescription())
                        .setParameters(parameter));
    };

    public static List<StandardToolRegister> buildToolRegister(ToolExecutor toolExecutor) {
        return toolExecutor.buildTool(TOOL_REGISTER_CONVERTER);
    }

    public static List<StandardToolRegister> buildToolRegister(ToolExecutor toolExecutor, List<Class<? extends ToolKit>> includeKits) {
        return toolExecutor.buildTool(TOOL_REGISTER_CONVERTER, includeKits);
    }

    /**
     * 根据指定的 Handler 名称过滤并构建工具注册信息
     *
     * @param toolExecutor    工具执行器
     * @param includeHandlers 需要包含的 Handler 名称集合
     * @return 转换后的 StandardToolRegister 列表
     */
    public static List<StandardToolRegister> buildToolRegisterByHandlers(ToolExecutor toolExecutor, Set<String> includeHandlers) {
        return toolExecutor.buildToolByHandlers(TOOL_REGISTER_CONVERTER, includeHandlers);
    }
}
