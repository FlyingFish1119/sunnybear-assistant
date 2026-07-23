package com.fishsunny.assistant.dto;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 05:36
 */

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class RestResponse {

    private int status;

    private String message;

    private Object data;

    public RestResponse() {
    }

    public RestResponse success(Object data) {
        this.status = 200;
        this.message = "success";
        this.data = data;
        return this;
    }

    public RestResponse error(String message) {
        this.status = 500;
        this.message = message;
        this.data = null;
        return this;
    }
}
