package com.fishsunny.assistant.settings;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 04:47
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserSettings {

    private String username = "用户";
    public UserSettings setUsername(String username) {
        this.username = username == null ? "用户" : username;
        return this;
    }
    public String getUsername() {
        return username == null ? "用户" : username;
    }

    private String avatar = "";
    public UserSettings setAvatar(String avatar) {
        this.avatar = avatar == null ? "" : avatar;
        return this;
    }
    public String getAvatar() {
        return avatar == null ? "" : avatar;

    }

    private String background = "";
    public UserSettings setBackground(String background) {
        this.background = background == null ? "" : background;
        return this;
    }
    public String getBackground() {
        return background == null ? "" : background;
    }

    private Double opacity;
    public UserSettings setOpacity(Double opacity) {
        this.opacity = opacity == null ? 0.3 : opacity;
        return this;
    }
    public Double getOpacity() {
        return opacity == null ? 0.3 : opacity;
    }

    private String mainColor;
    public UserSettings setMainColor(String mainColor) {
        this.mainColor = mainColor == null ? "lightsalmon" : mainColor;
        return this;
    }
    public String getMainColor() {
        return mainColor == null ? "lightsalmon" : mainColor;
    }

    private Boolean enableAutoSwitchModel = false;
    public UserSettings setEnableAutoSwitchModel(Boolean enableAutoSwitchModel) {
        this.enableAutoSwitchModel = Boolean.TRUE.equals(enableAutoSwitchModel);
        return this;
    }
    public Boolean getEnableAutoSwitchModel() {
        return Boolean.TRUE.equals(enableAutoSwitchModel);
    }

    /**
     * 自动切换高级模型的判定阈值：复杂度评分大于该值（不含等于）才切到高级模型。
     * 取值 0-1，默认 0.7；越小越容易切高级模型（0 = 几乎全切，1 = 永不自动切）。
     */
    private Double proModelThreshold;
    public UserSettings setProModelThreshold(Double proModelThreshold) {
        if (proModelThreshold == null) {
            this.proModelThreshold = 0.7;
        } else if (proModelThreshold < 0) {
            this.proModelThreshold = 0.0;
        } else if (proModelThreshold > 1) {
            this.proModelThreshold = 1.0;
        } else {
            this.proModelThreshold = proModelThreshold;
        }
        return this;
    }
    public Double getProModelThreshold() {
        return proModelThreshold == null ? 0.7 : proModelThreshold;
    }

    /**
     * 上下文 token 上限：单轮请求里消息（不含 system）的估算总 token 超过该值时触发一次压缩。
     * null 或 &lt;= 0 表示关闭该功能。
     */
    private Long contextTokenLimit;
    public UserSettings setContextTokenLimit(Long contextTokenLimit) {
        this.contextTokenLimit = contextTokenLimit;
        return this;
    }
    public Long getContextTokenLimit() {
        return contextTokenLimit;
    }

    /**
     * 图片最长边像素上限：发送给模型前，图片最长边超过该值时等比压缩，用于控制请求体积。
     * &lt;= 0 表示不压缩；null 取默认值 1024。
     */
    private Integer maxImageEdgePixel;
    public UserSettings setMaxImageEdgePixel(Integer maxImageEdgePixel) {
        this.maxImageEdgePixel = maxImageEdgePixel;
        return this;
    }
    public Integer getMaxImageEdgePixel() {
        return maxImageEdgePixel == null ? 1024 : maxImageEdgePixel;
    }
}
