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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.spi.SchemaValidityCheck;

/**
 * Check that every answer option has a value, and no two options of a question use the same value.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SchemaValidityCheck.class)
public class AnswerOptionsCheck implements SchemaValidityCheck
{
    @Override
    public List<String> check(final Resource version)
    {
        return DraftParts.ofType(version, DraftParts.QUESTION)
            .flatMap(part -> problems(DraftParts.asQuestion(part).getOptions(), DraftParts.describe(part, version))
                .stream())
            .toList();
    }

    private static List<String> problems(final List<AnswerOption> options, final String where)
    {
        final List<String> problems = new ArrayList<>();
        final Set<String> values = new HashSet<>();
        for (final AnswerOption option : options) {
            final String value = option.getValue();
            if (value == null || value.isBlank()) {
                problems.add(where + " has an option without a value");
            } else if (!values.add(value)) {
                problems.add(where + " offers the value \"" + value + "\" more than once");
            }
        }
        return problems;
    }
}
