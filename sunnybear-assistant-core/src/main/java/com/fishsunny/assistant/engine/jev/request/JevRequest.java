package com.fishsunny.assistant.engine.jev.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:20
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JevRequest {

    private String state;

    private String model;

    /**
     * 问题集合：key 为调用方自定义的问题 id，value 为问题对象（choice / score / noul）。
     */
    private Map<String, JevQuestion> questions = new LinkedHashMap<>();

    public JevRequest setQuestions(Map<String, JevQuestion> questions) {
        this.questions = questions == null ? new LinkedHashMap<>() : questions;
        return this;
    }

    public JevRequest() {
    }

    public static class Builder {

        private final JevRequest request;

        public static Builder create(String state) {
            return new Builder(state);
        }

        protected Builder(String state) {
            request = new JevRequest();
            request.setState(state);
        }

        public JevRequest build() {
            return request;
        }

        public Builder noulQuestion(String key, String instructions) {
            return this.noulQuestion(key, instructions, null, null);
        }
        public Builder noulQuestion(String key, String instructions, String forYes, String forNo) {
            JevNoulQuestion noulQuestion = new JevNoulQuestion(forYes, forNo);
            noulQuestion.setInstructions(instructions);
            request.getQuestions().put(key, noulQuestion);
            return this;
        }

        public Builder choiceQuestion(String key, String instructions) {
            JevChoiceQuestion choiceQuestion = new JevChoiceQuestion();
            choiceQuestion.setInstructions(instructions);
            request.getQuestions().put(key, choiceQuestion);
            return this;
        }

        public Builder choiceQuestionSelector(String key, String selectorKey, String selectorVal) {
            JevQuestion jevQuestion = request.getQuestions().get(key);
            if (!(jevQuestion instanceof JevChoiceQuestion)) {
                throw new IllegalArgumentException("Question is not a choice question");
            }
            ((JevChoiceQuestion) jevQuestion).getCriteria().put(selectorKey, selectorVal);
            return this;
        }
    }
}
