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
package io.uhndata.iap.datarequirement.internal;

import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Serializes the catalogue version a data requirement names as a summary: its path as {@code @path}, its
 * {@code version} label, and its catalogue's path and {@code title} under {@code catalogue}. Without it,
 * {@code dereference} inlines every database, collection and field of the version into each schema version and
 * submission that holds the requirement. A page that shows the catalogue loads the version itself. The processor is
 * named {@code catalogueSummary} and is on by default.
 *
 * <p>A name the reader may not see is left out. For example:</p>
 *
 * {@snippet lang=json :
 * {
 *   "@path": "/Catalogues/registry/v2",
 *   "version": "2026-08",
 *   "catalogue": {"@path": "/Catalogues/registry", "title": "Clinical registry"}
 * }
 * }
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class CatalogueSummaryProcessor implements ResourceJsonProcessor
{
    private static final String PROCESSOR_NAME = "catalogueSummary";

    /** The node type holding the summarized reference. */
    private static final String REQUIREMENT_TYPE = "datareq:DataRequirement";

    /** The REFERENCE property naming the catalogue version. */
    private static final String CATALOGUE_PROPERTY = "catalogue";

    /** The catalogue version's readable label. */
    private static final String VERSION_PROPERTY = "version";

    /** The catalogue's readable name. */
    private static final String TITLE_PROPERTY = "title";

    /** The JSON key holding the summary of the version's catalogue. */
    private static final String CATALOGUE_KEY = "catalogue";

    @Override
    public String getName()
    {
        return PROCESSOR_NAME;
    }

    @Override
    public int getPriority()
    {
        // After `dereference` (10), whose inlined catalogue version this replaces
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
            if (CATALOGUE_PROPERTY.equals(property.getName()) && node.isNodeType(REQUIREMENT_TYPE)) {
                return summarizeVersion(property.getNode());
            }
        } catch (final RepositoryException e) {
            // A catalogue version the reader cannot reach is left as `dereference` left it
        }
        return input;
    }

    private JsonValue summarizeVersion(final Node version) throws RepositoryException
    {
        final JsonObjectBuilder summary = summarize(version, VERSION_PROPERTY);
        summary.add(CATALOGUE_KEY, summarize(version.getParent(), TITLE_PROPERTY));
        return summary.build();
    }

    private JsonObjectBuilder summarize(final Node node, final String nameProperty) throws RepositoryException
    {
        final JsonObjectBuilder summary = Json.createObjectBuilder().add("@path", node.getPath());
        if (node.hasProperty(nameProperty)) {
            summary.add(nameProperty, node.getProperty(nameProperty).getString());
        }
        return summary;
    }
}
