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

package io.uhndata.iap.serialization.internal;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Serves the date content brought over from another instance was created there as its {@code jcr:created}.
 *
 * <p>
 * Oak stamps the {@code jcr:created} property and lets nobody set it, so content from another instance would otherwise
 * date from its arrival here. It keeps its original date in {@code originalCreated}, and this puts that date where
 * every reader already looks, leaving the arrival out. Enabled by default: content without the property is left alone.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, service = ResourceJsonProcessor.class)
public class OriginalCreatedProcessor implements ResourceJsonProcessor
{
    /** Where content brought over from another instance keeps the date it was created there. */
    static final String ORIGINAL_CREATED_PROPERTY = "originalCreated";

    private static final String CREATED_PROPERTY = "jcr:created";

    private static final Logger LOGGER = LoggerFactory.getLogger(OriginalCreatedProcessor.class);

    @Override
    public String getName()
    {
        return "originalCreated";
    }

    @Override
    public int getPriority()
    {
        // After `simple` at 25, so that what it drops stays dropped
        return 30;
    }

    @Override
    public boolean isEnabledByDefault(@NotNull final Resource resource)
    {
        return true;
    }

    @Override
    public String processPropertyName(@NotNull final Node node, @NotNull final Property property,
        @Nullable final String input)
    {
        if (property == null || input == null) {
            return input;
        }
        try {
            final String actual = property.getName();
            if (ORIGINAL_CREATED_PROPERTY.equals(actual)) {
                return CREATED_PROPERTY;
            }
            if (CREATED_PROPERTY.equals(actual) && node.hasProperty(ORIGINAL_CREATED_PROPERTY)) {
                return null;
            }
            return input;
        } catch (final RepositoryException e) {
            LOGGER.warn("Unexpected error while naming property {} of {}: {}", property, node, e.getMessage());
            return input;
        }
    }
}
