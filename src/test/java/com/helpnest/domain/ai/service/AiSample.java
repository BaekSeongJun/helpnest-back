// @owner SSJ
package com.helpnest.domain.ai.service;

import com.helpnest.domain.ai.dto.ClassificationResult.Category;
import com.helpnest.domain.ai.dto.ClassificationResult.Sentiment;

/** src/test/resources/ai/samples.json 한 건 (docs/05 §6) */
record AiSample(String title, String content, String categoryHint, Category expectedCategory,
        Sentiment expectedSentiment) {
}
