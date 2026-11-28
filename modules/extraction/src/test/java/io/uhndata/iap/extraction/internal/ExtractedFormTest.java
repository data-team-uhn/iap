/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.uhndata.iap.extraction.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ExtractedForm}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ExtractedFormTest
{
    // Answers are created in this order, so a hash order would shuffle them on the submission
    @Test
    void keepsTheOrderTheQuestionsWereAskedIn()
    {
        final List<String> keys = IntStream.range(0, 20).mapToObj(index -> "q" + (19 - index)).toList();
        final Map<String, String> questions = new LinkedHashMap<>();
        final Map<String, FieldResult> results = new LinkedHashMap<>();
        keys.forEach(key -> {
            questions.put(key, "/Schemas/proposal/v1/study/" + key);
            results.put(key, FieldResult.createNotFound(key));
        });

        final ExtractedForm form = new ExtractedForm(questions, results, List.of());

        assertEquals(keys, new ArrayList<>(form.questions().keySet()));
        assertEquals(keys, new ArrayList<>(form.results().keySet()));
    }

    @Test
    void cannotChangeAfterItIsMade()
    {
        final Map<String, String> questions = new LinkedHashMap<>(Map.of("title", "/Schemas/proposal/v1/title"));
        final Map<String, FieldResult> results = new LinkedHashMap<>(Map.of("title",
            FieldResult.createNotFound("title")));
        final List<String> files = new ArrayList<>(List.of("/Submissions/s1/d1/v1/file"));
        final ExtractedForm form = new ExtractedForm(questions, results, files);
        questions.clear();
        results.clear();
        files.clear();

        assertEquals(1, form.questions().size());
        assertEquals(1, form.results().size());
        assertEquals(1, form.files().size());
        assertThrows(UnsupportedOperationException.class, () -> form.questions().clear());
        assertThrows(UnsupportedOperationException.class, () -> form.results().clear());
        assertThrows(UnsupportedOperationException.class, () -> form.files().clear());
    }
}
