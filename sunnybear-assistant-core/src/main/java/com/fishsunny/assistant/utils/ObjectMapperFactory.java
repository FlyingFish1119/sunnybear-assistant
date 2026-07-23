package com.fishsunny.assistant.utils;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 03:56
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ObjectMapperFactory {

    @Getter
    private static ObjectMapper objectMapper;

    @Autowired
    public ObjectMapperFactory(ObjectMapper objectMapper) {
        ObjectMapperFactory.objectMapper = objectMapper;
    }
}
