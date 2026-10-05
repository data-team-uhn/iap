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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.spi.SchemaValidityCheck;

/**
 * Check that every pattern a question's answers must match is a valid regular expression.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SchemaValidityCheck.class)
public class QuestionPatternCheck implements SchemaValidityCheck
{
    @Override
    public List<String> check(final Resource version)
    {
        return DraftParts.ofType(version, DraftParts.QUESTION)
            .filter(part -> !compiles(DraftParts.asQuestion(part).getPattern()))
            .map(part -> DraftParts.describe(part, version) + " has a pattern that is not a valid regular expression")
            .toList();
    }

    private static boolean compiles(final String pattern)
    {
        if (pattern == null) {
            return true;
        }
        try {
            Pattern.compile(pattern);
            return true;
        } catch (final PatternSyntaxException e) {
            return false;
        }
    }
}
