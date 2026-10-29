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
package io.uhndata.iap.submissions.internal;

import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Prints the schema a submission belongs to as its path, where {@code dereference} would inline the whole schema
 * with every one of its versions. The submission's own {@code schemaVersion} already carries the version it
 * answers. The name of this processor is {@code schemaPath}, and it is enabled by default.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class SubmissionSchemaProcessor implements ResourceJsonProcessor
{
    private static final String PROCESSOR_NAME = "schemaPath";

    private static final String SUBMISSION_TYPE = "sub:Submission";

    /** The reference from a submission to its schema. */
    private static final String SCHEMA_PROPERTY = "schema";

    @Override
    public String getName()
    {
        return PROCESSOR_NAME;
    }

    @Override
    public int getPriority()
    {
        // After `dereference` (10), whose inlined schema this replaces
        return 50;
    }

    @Override
    public boolean isEnabledByDefault(final Resource resource)
    {
        return true;
    }

    @Override
    public JsonValue processProperty(final Node node, final Property property,
        final JsonValue input, final Function<Node, JsonValue> serializeNode)
    {
        try {
            if (SCHEMA_PROPERTY.equals(property.getName()) && node.isNodeType(SUBMISSION_TYPE)) {
                return Json.createValue(property.getNode().getPath());
            }
        } catch (final RepositoryException e) {
            // A schema the reader cannot reach is left as `dereference` left it
        }
        return input;
    }
}
