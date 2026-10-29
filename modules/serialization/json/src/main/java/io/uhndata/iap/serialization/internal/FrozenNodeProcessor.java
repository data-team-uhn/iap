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
 * Makes a past state of a resource serialize as the thing it is a copy of, rather than as the repository's copy of it.
 *
 * <p>
 * Every node of a checked-in subtree is an {@code nt:frozenNode}, carrying the original's type and identifier under
 * {@code jcr:frozenPrimaryType} and {@code jcr:frozenUuid}. The {@code simple} processor drops both. This serves them
 * as {@code jcr:primaryType} and {@code jcr:uuid}, and leaves out the copy's own type and identifier and the
 * original's mixins.
 * </p>
 *
 * <p>
 * Enabled by default when the resource requested is frozen, since nothing downstream could otherwise tell that what it
 * reads is old.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, service = ResourceJsonProcessor.class)
public class FrozenNodeProcessor implements ResourceJsonProcessor
{
    /** The type the repository gives its copies. */
    static final String FROZEN_NODE_TYPE = "nt:frozenNode";

    private static final Logger LOGGER = LoggerFactory.getLogger(FrozenNodeProcessor.class);

    /** What the copy calls the original's own bookkeeping, and what a reader knows it as. */
    private static final Map<String, String> RESTORED_NAMES = Map.of(
        "jcr:frozenPrimaryType", "jcr:primaryType",
        "jcr:frozenUuid", "jcr:uuid");

    /** What a past state is served without. */
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
        // After `simple` at 25, which drops both of the properties this puts back
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
            // `input` is ignored: `simple` drops these, and this puts them back
            return RESTORED_NAMES.getOrDefault(actual, input);
        } catch (final RepositoryException e) {
            LOGGER.warn("Unexpected error while naming property {} of frozen node {}: {}", property, node,
                e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(FrozenNodeProcessor.class, "processPropertyName"));
            return input;
        }
    }

    /**
     * Whether this node is one of the repository's copies. A node that cannot be asked is treated as not frozen, so a
     * serialization never fails over a question about the content it is serializing.
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
