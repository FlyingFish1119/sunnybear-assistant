package com.fishsunny.assistant.utils;

/*
 * @Usage 余弦相似度计算工具
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import java.util.List;

public class CosineSimilarityUtil {

    /**
     * 计算两个向量的余弦相似度
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 余弦相似度，范围 [-1, 1]。若任一向量模长为 0，返回 0
     * @throws IllegalArgumentException 向量长度不一致时抛出
     */
    public static float cosine(List<Float> a, List<Float> b) {
        if (a.size() != b.size()) {
            throw new IllegalArgumentException("向量维度不一致: a=" + a.size() + ", b=" + b.size());
        }

        float dotProduct = 0f;
        float normA = 0f;
        float normB = 0f;

        for (int i = 0; i < a.size(); i++) {
            float va = a.get(i);
            float vb = b.get(i);
            dotProduct += va * vb;
            normA += va * va;
            normB += vb * vb;
        }

        if (normA == 0f || normB == 0f) {
            return 0f;
        }

        return dotProduct / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
