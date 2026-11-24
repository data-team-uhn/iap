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
package io.uhndata.iap.workflows.internal.checks;

import java.util.Arrays;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ValidityCheck;

/**
 * Check that a version's diagram was fully saved. A version is tagged {@code invalid} while the last parse of its
 * diagram reported problems, which are stored on it as {@code parseProblems}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ValidityCheck.class)
public class InvalidVersionCheck implements ValidityCheck
{
    private static final String INVALID_TAG = "invalid";

    private static final String TAGS_PROPERTY = "tags";

    private static final String PARSE_PROBLEMS_PROPERTY = "parseProblems";

    private static final String NOT_SAVED_MESSAGE = "its diagram was not fully saved";

    @Override
    public boolean appliesTo(final Resource target)
    {
        return target.isResourceType(WorkflowVersion.RESOURCE_TYPE);
    }

    @Override
    public List<String> check(final Resource version)
    {
        final ValueMap properties = version.getValueMap();
        if (!Arrays.asList(properties.get(TAGS_PROPERTY, new String[0])).contains(INVALID_TAG)) {
            return List.of();
        }
        final String[] problems = properties.get(PARSE_PROBLEMS_PROPERTY, new String[0]);
        return problems.length == 0 ? List.of(NOT_SAVED_MESSAGE)
            : Arrays.stream(problems).map(problem -> NOT_SAVED_MESSAGE + ": " + problem).toList();
    }
}
