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

import java.util.List;
import java.util.Map;

/**
 * What the model read for one form, as the main extraction hands it to {@link EndReadingHandler}. Paths, not
 * resources: the handler resolves them through the engine's session.
 *
 * @param questions the path of each question asked, by {@link ExtractionField#getKey}
 * @param results what the model answered, keyed the same way
 * @param files the paths of the uploads it read
 * @version $Id$
 * @since 0.1.0
 */
record ExtractedForm(Map<String, String> questions, Map<String, FieldResult> results, List<String> files)
{
    /**
     * Copies the collections, so a reading cannot change after it is made.
     *
     * @param questions the path of each question asked
     * @param results what the model answered
     * @param files the paths of the uploads it read
     */
    ExtractedForm
    {
        questions = Map.copyOf(questions);
        results = Map.copyOf(results);
        files = List.copyOf(files);
    }
}
