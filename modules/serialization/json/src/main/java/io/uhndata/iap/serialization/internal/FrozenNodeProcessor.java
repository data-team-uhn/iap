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
 * Makes a past state of a resource serialize as the thing it is a copy of, rather than as the repository's copy of it.
 *
 * <p>
 * When a resource is checked in, the repository keeps its content in a {@code nt:frozenNode} under version storage.
 * That copy carries the original's real type and identity under different names — {@code jcr:frozenPrimaryType} and
 * {@code jcr:frozenUuid} — while its own {@code jcr:primaryType} says {@code nt:frozenNode}. Serialized as it stands,
 * a past state therefore reports a type nothing in the application has ever heard of, says nothing about which
 * resource it is a copy of, and does so for every node in the subtree rather than only at the top; the two properties
 * that would have answered both questions are dropped as repository bookkeeping.
 * </p>
 *
 * <p>
 * This puts them back where a reader expects them: the frozen type is served as the type, the frozen identity as the
 * identity, and the copy's own two are left out. Enabled by default whenever what is being serialized is frozen, since
 * an unannounced past state is worse than no past state — nothing downstream can tell that what it is looking at is
 * old, which is exactly the mistake a history view must not make.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, service = ResourceJsonProcessor.class)
public class FrozenNodeProcessor implements ResourceJsonProcessor
{
    /** The type the repository gives its copies. */
    static final String FROZEN_NODE = "nt:frozenNode";

    private static final Logger LOGGER = LoggerFactory.getLogger(FrozenNodeProcessor.class);

    /** What the copy calls the original's own bookkeeping, and what a reader knows it as. */
    private static final Map<String, String> RESTORED = Map.of(
        "jcr:frozenPrimaryType", "jcr:primaryType",
        "jcr:frozenUuid", "jcr:uuid");

    /**
     * What describes the copy rather than the content. Only {@code jcr:primaryType} is measurably there — a frozen node
     * carries no {@code jcr:uuid} of its own in Oak 2.4.0 — but a copy's identity has no business being served as the
     * content's whatever the repository decides to write, so it is named here rather than relied on to be absent.
     */
    private static final Map<String, String> SUPPRESSED = Map.of(
        "jcr:primaryType", "the copy's own type, which is always nt:frozenNode",
        "jcr:uuid", "the copy's own identifier, if the repository ever writes one",
        "jcr:frozenMixinTypes", "the original's mixins, which are bookkeeping either way");

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
        if (property == null || !isFrozen(node)) {
            return input;
        }
        try {
            final String actual = property.getName();
            if (SUPPRESSED.containsKey(actual)) {
                return null;
            }
            // Deliberately ignoring `input`: `simple` will have dropped these, and putting them back is the point
            return RESTORED.getOrDefault(actual, input);
        } catch (final RepositoryException e) {
            LOGGER.warn("Unexpected error while naming property {} of frozen node {}: {}", property, node,
                e.getMessage());
            return input;
        }
    }

    /**
     * Whether this node is one of the repository's copies. A serialization must never fail over a question about the
     * content it is serializing, so a node that cannot be asked is treated as not frozen: that leaves the output as it
     * would have been without this processor rather than half-corrected.
     */
    private static boolean isFrozen(final Node node)
    {
        try {
            return node != null && node.isNodeType(FROZEN_NODE);
        } catch (final RepositoryException e) {
            LOGGER.warn("Unexpected error while checking whether {} is a frozen node: {}", node, e.getMessage());
            return false;
        }
    }
}
