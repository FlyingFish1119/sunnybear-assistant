package com.fishsunny.assistant.engine.jev;


import com.fishsunny.assistant.engine.jev.request.JevRequest;
import com.fishsunny.assistant.engine.jev.response.JevAnswer;
import com.fishsunny.assistant.engine.jev.response.JevChoiceAnswer;
import com.fishsunny.assistant.engine.jev.response.JevNoulAnswer;
import com.fishsunny.assistant.engine.jev.response.JevResponse;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.util.StringUtils;

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
        JevAnswer answer = getAnswer(key, JevNoulAnswer.class);
        if (answer instanceof JevNoulAnswer jevNoulAnswer) {
            return jevNoulAnswer.getNoul() == null ? 0 : jevNoulAnswer.getNoul();
        } else {
            throw new JevException("The answer is not a NoulAnswer");
        }
    }

    public Map<String, Double> mappingChoice(String key) {
        JevAnswer answer = getAnswer(key, JevChoiceAnswer.class);
        if (answer instanceof JevChoiceAnswer jevChoiceAnswer) {
            return jevChoiceAnswer.getProbabilities();
        } else {
            throw new JevException("The answer is not a ChoiceAnswer");
        }
    }

    public Double mappingBestChoice(String key) {
        JevAnswer answer = getAnswer(key, JevChoiceAnswer.class);
        if (answer instanceof JevChoiceAnswer jevChoiceAnswer) {
            String bestChoice = jevChoiceAnswer.getChoice();
            if (!StringUtils.hasText(bestChoice)) {
                return 0d;
            }
            Double value = jevChoiceAnswer.getProbabilities().get(bestChoice);
            return value == null ? 0d : value;
        } else {
            throw new JevException("The answer is not a ChoiceAnswer");
        }
    }

    private <T extends JevAnswer> T getAnswer(String key, Class<T> clazz) {
        Map<String, JevAnswer> answers = response.getAnswers();
        JevAnswer answer = answers.get(key);
        if (answer == null) {
            throw new JevException("No such key: " + key);
        }
        if (!clazz.isInstance(answer)) {
            throw new JevException("The answer is not a " + clazz.getName());
        }
        return clazz.cast(answer);
    }
}
