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

import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Serializes a past state of a resource as the content it is a copy of, not as the JCR version's copy.
 *
 * <p>
 * A JCR version keeps its copy of a subtree as {@code nt:frozenNode} nodes. Each one holds the original's type and
 * {@code jcr:uuid} under {@code jcr:frozenPrimaryType} and {@code jcr:frozenUuid}, which the {@code simple} processor
 * drops. This processor writes them as {@code jcr:primaryType} and {@code jcr:uuid}. It leaves out the copy's own type
 * and {@code jcr:uuid}, and the original's mixins.
 * </p>
 *
 * <p>
 * The name of this processor is {@code frozen}. It is enabled by default when the requested resource is an
 * {@code nt:frozenNode}.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, service = ResourceJsonProcessor.class)
public class FrozenNodeProcessor implements ResourceJsonProcessor
{
    /** The node type of a JCR version's copy of a node. */
    static final String FROZEN_NODE_TYPE = "nt:frozenNode";

    private static final Logger LOGGER = LoggerFactory.getLogger(FrozenNodeProcessor.class);

    /** The copy's name for each property of the original, mapped to the original's name. */
    private static final Map<String, String> RESTORED_NAMES = Map.of(
        "jcr:frozenPrimaryType", "jcr:primaryType",
        "jcr:frozenUuid", "jcr:uuid");

    /** The properties of the copy left out of the JSON. */
    private static final Set<String> SUPPRESSED_PROPERTIES = Set.of("jcr:primaryType", "jcr:uuid",
        "jcr:frozenMixinTypes");

    @Override
    public String getName()
    {
        return "frozen";
    }

    @Override
    public int getPriority()
    {
        // After `simple`, at 25, which drops the two properties this writes back
        return 30;
    }

    @Override
    public boolean isEnabledByDefault(@NotNull final Resource resource)
    {
        final Node node = resource.adaptTo(Node.class);
        return node != null && isFrozen(node);
    }

    @Override
    public String processPropertyName(@NotNull final Node node, @NotNull final Property property,
        @Nullable final String input)
    {
        if (!isFrozen(node)) {
            return input;
        }
        try {
            final String actual = property.getName();
            if (SUPPRESSED_PROPERTIES.contains(actual)) {
                return null;
            }
            // Renames by the property's own name: under `simple`, `input` is already null for these two
            return RESTORED_NAMES.getOrDefault(actual, input);
        } catch (final RepositoryException e) {
            LOGGER.warn("Unexpected error while naming property {} of frozen node {}: {}", property, node,
                e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(FrozenNodeProcessor.class, "processPropertyName"));
            return input;
        }
    }

    /**
     * Whether this node is a JCR version's copy of a node. A node whose type cannot be read counts as not frozen, and
     * is serialized as it is stored.
     */
    private static boolean isFrozen(final Node node)
    {
        try {
            return node.isNodeType(FROZEN_NODE_TYPE);
        } catch (final RepositoryException e) {
            LOGGER.warn("Unexpected error while checking whether {} is a frozen node: {}", node, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(FrozenNodeProcessor.class, "isFrozen"));
            return false;
        }
    }
}
