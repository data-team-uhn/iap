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
package io.uhndata.iap.schemas.editing.internal;

import java.util.List;
import java.util.stream.Stream;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.spi.SchemaValidityCheck;

/**
 * Check that no question's bounds are reversed.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SchemaValidityCheck.class)
public class QuestionBoundsCheck implements SchemaValidityCheck
{
    @Override
    public List<String> check(final Resource version)
    {
        return DraftParts.ofType(version, DraftParts.QUESTION)
            .flatMap(part -> problems(DraftParts.asQuestion(part), DraftParts.describe(part, version)))
            .toList();
    }

    private static Stream<String> problems(final Question question, final String where)
    {
        final Stream.Builder<String> problems = Stream.builder();
        if (question.getMaxAnswers() > 0 && question.getMinAnswers() > question.getMaxAnswers()) {
            problems.add(where + " asks for at least " + question.getMinAnswers() + " answers but allows at most "
                + question.getMaxAnswers());
        }
        final Double min = question.getMinValue();
        final Double max = question.getMaxValue();
        if (min != null && max != null && min > max) {
            problems.add(where + " has a smallest accepted value above its largest");
        }
        return problems.build();
    }
}
