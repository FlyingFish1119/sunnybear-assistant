package com.fishsunny.assistant.engine.jev;


import com.fishsunny.assistant.engine.jev.request.JevRequest;
import com.fishsunny.assistant.engine.jev.response.JevAnswer;
import com.fishsunny.assistant.engine.jev.response.JevNoulAnswer;
import com.fishsunny.assistant.engine.jev.response.JevResponse;

import java.util.Map;

/**
 * NoulResponseRelover
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:56
 */
public class JevResponseMapper {

    private final JevResponse response;

    public JevResponseMapper(JevResponse response) {
        this.response = response;
    }

    public Double mappingNoul(String key) {
        Map<String, JevAnswer> answers = response.getAnswers();
        JevAnswer answer = answers.get(key);
        if (answer == null) {
            throw new JevException("No such key: " + key);
        }
        if (answer instanceof JevNoulAnswer jevNoulAnswer) {
            return jevNoulAnswer.getNoul() == null ? 0 : jevNoulAnswer.getNoul();
        } else {
            throw new JevException("The answer is not a NoulAnswer");
        }
    }
}
