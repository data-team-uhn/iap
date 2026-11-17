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
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Serializes the {@code schema} of a submission as a summary of the schema: its path as {@code @path} and its
 * {@code title}. {@code dereference} would inline the whole schema with all its versions, and a reader needs only to
 * name it. The submission's {@code schemaVersion} already holds the version it answers. The processor is
 * named {@code schemaSummary} and is on by default.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class SubmissionSchemaProcessor implements ResourceJsonProcessor
{
    private static final String PROCESSOR_NAME = "schemaSummary";

    private static final String SUBMISSION_TYPE = "sub:Submission";

    /** The reference from a submission to its schema. */
    private static final String SCHEMA_PROPERTY = "schema";

    private static final String TITLE_PROPERTY = "title";

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
                return summaryOf(property.getNode());
            }
        } catch (final RepositoryException e) {
            // A schema the reader cannot reach is left as `dereference` left it
        }
        return input;
    }

    private JsonObject summaryOf(final Node schema) throws RepositoryException
    {
        final JsonObjectBuilder summary = Json.createObjectBuilder().add("@path", schema.getPath());
        // A title the reader may not see is left out
        if (schema.hasProperty(TITLE_PROPERTY)) {
            summary.add(TITLE_PROPERTY, schema.getProperty(TITLE_PROPERTY).getString());
        }
        return summary.build();
    }
}
