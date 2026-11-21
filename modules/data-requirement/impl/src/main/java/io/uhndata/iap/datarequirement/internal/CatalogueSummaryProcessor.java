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

import java.util.List;
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
 * Serializes the catalogue a data requirement names, and the catalogue version a selection records, as a summary:
 * the path as {@code @path} and the readable name. {@code dereference} would inline every database, collection and
 * field of it into each submission that holds a selection. A reader needs only to name it, and a page that shows the
 * catalogue loads its version on its own. The processor is named {@code catalogueSummary} and is on by default.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class CatalogueSummaryProcessor implements ResourceJsonProcessor
{
    private static final String PROCESSOR_NAME = "catalogueSummary";

    /** The references summarized, each with the property that names what it points at. */
    private static final List<SummarizedReference> SUMMARIZED_REFERENCES = List.of(
        new SummarizedReference("datareq:DataRequirement", "catalogue", "title"),
        new SummarizedReference("datareq:Selection", "catalogueVersion", "version"));

    @Override
    public String getName()
    {
        return PROCESSOR_NAME;
    }

    @Override
    public int getPriority()
    {
        // After `dereference` (10), whose inlined catalogue this replaces
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
            for (final SummarizedReference reference : SUMMARIZED_REFERENCES) {
                if (reference.isHeldBy(node, property)) {
                    return reference.summaryOf(property.getNode());
                }
            }
        } catch (final RepositoryException e) {
            // A catalogue the reader cannot reach is left as `dereference` left it
        }
        return input;
    }

    /**
     * One reference to summarize.
     *
     * @param nodeType the node type holding the reference
     * @param property the reference property
     * @param nameProperty the property of the target that names it for a reader
     * @since 0.1.0
     */
    private record SummarizedReference(String nodeType, String property, String nameProperty)
    {
        boolean isHeldBy(final Node node, final Property candidate) throws RepositoryException
        {
            return this.property.equals(candidate.getName()) && node.isNodeType(this.nodeType);
        }

        JsonValue summaryOf(final Node target) throws RepositoryException
        {
            final JsonObjectBuilder summary = Json.createObjectBuilder().add("@path", target.getPath());
            // A name the reader may not see is left out
            if (target.hasProperty(this.nameProperty)) {
                summary.add(this.nameProperty, target.getProperty(this.nameProperty).getString());
            }
            return summary.build();
        }
    }
}
