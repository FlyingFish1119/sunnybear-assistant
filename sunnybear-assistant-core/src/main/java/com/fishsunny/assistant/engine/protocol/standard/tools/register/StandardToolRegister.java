package com.fishsunny.assistant.engine.protocol.standard.tools.register;

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
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
        Map<String, StandardToolRegisterProperty> properties = new LinkedHashMap<>();
        List<ToolRegister.Parameters> params = register.getParameters();
        if (params != null) {
            for (ToolRegister.Parameters parameter : params) {
                properties.put(parameter.getParameterName(), toProperty(parameter));
            }
        }
        StandardToolRegisterParameter parameter = new StandardToolRegisterParameter()
                .setProperties(properties).setRequired(register.getRequired());
        return new StandardToolRegister()
                .setFunction(new StandardToolRegisterFunction()
                        .setName(register.getName())
                        .setDescription(register.getDescription())
                        .setParameters(parameter));
    };

    /** 递归转换参数 schema：对象字段与数组元素都要带上（严格实现如 Gemini 要求 array 必须给出 items） */
    private static StandardToolRegisterProperty toProperty(ToolRegister.Parameters parameter) {
        StandardToolRegisterProperty property = new StandardToolRegisterProperty()
                .setType(parameter.getType())
                .setDescription(parameter.getDescription());

        if (parameter.getItems() != null) {
            property.setItems(toProperty(parameter.getItems()));
        }
        if (parameter.getProperties() != null) {
            Map<String, StandardToolRegisterProperty> nested = new LinkedHashMap<>();
            for (ToolRegister.Parameters child : parameter.getProperties()) {
                nested.put(child.getParameterName(), toProperty(child));
            }
            property.setProperties(nested);
        }
        property.setRequired(parameter.getRequired());
        return property;
    }

    public static List<StandardToolRegister> buildToolRegister(ToolExecutor toolExecutor) {
        return toolExecutor.buildTool(TOOL_REGISTER_CONVERTER);
    }

    public static List<StandardToolRegister> buildToolRegister(ToolExecutor toolExecutor, List<Class<? extends ToolKit>> includeKits) {
        return toolExecutor.buildTool(TOOL_REGISTER_CONVERTER, includeKits);
    }

    /**
     * 构建所有工具注册信息，排除指定的 Handler 名称
     *
     * @param toolExecutor    工具执行器
     * @param excludeHandlers 需要排除的 Handler 名称集合
     * @return 转换后的 StandardToolRegister 列表
     */
    public static List<StandardToolRegister> buildToolRegisterExcluding(ToolExecutor toolExecutor, Set<String> excludeHandlers) {
        return toolExecutor.buildToolExcluding(TOOL_REGISTER_CONVERTER, excludeHandlers);
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
