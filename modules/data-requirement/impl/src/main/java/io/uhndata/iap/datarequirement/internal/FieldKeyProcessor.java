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

import java.util.Optional;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.datarequirement.models.Field;
import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Adds to each catalogue field the {@code key} a selection records it as, so that a reader takes the key from
 * {@link Field#getKey()} instead of building it again. The processor is named {@code fieldKey} and is on by default.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class FieldKeyProcessor implements ResourceJsonProcessor
{
    private static final String PROCESSOR_NAME = "fieldKey";

    private static final String FIELD_NODE_TYPE = "datareq:Field";

    private static final String KEY_PROPERTY = "key";

    /** The resolver of the resource being serialized, which the fields are read through. */
    private final ThreadLocal<ResourceResolver> resolver = new ThreadLocal<>();

    @Override
    public String getName()
    {
        return PROCESSOR_NAME;
    }

    @Override
    public int getPriority()
    {
        return 50;
    }

    @Override
    public boolean isEnabledByDefault(final Resource resource)
    {
        return true;
    }

    @Override
    public void start(final Resource resource)
    {
        this.resolver.set(resource.getResourceResolver());
    }

    @Override
    public void leave(final Node node, final JsonObjectBuilder json, final Function<Node, JsonValue> serializeNode)
    {
        try {
            if (node.isNodeType(FIELD_NODE_TYPE)) {
                Optional.ofNullable(this.resolver.get().getResource(node.getPath()))
                    .map(resource -> resource.adaptTo(Field.class))
                    .map(Field::getKey)
                    .ifPresent(key -> json.add(KEY_PROPERTY, key));
            }
        } catch (final RepositoryException e) {
            // A field the reader cannot reach gets no key, and cannot be chosen
        }
    }

    @Override
    public void end(final Resource resource)
    {
        this.resolver.remove();
    }
}
