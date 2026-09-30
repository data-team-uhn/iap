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
package io.uhndata.iap.auth.author;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeState;

/**
 * Answers whether a node's type declares {@code auth:lastAuthor}, i.e. whether its primary type, one of its mixins,
 * or one of their supertypes carries the {@code auth:Authored} mixin. Verdicts are cached per type name against the
 * node type registry materialized at {@code /jcr:system/jcr:nodeTypes}, so one instance must not outlive the commit
 * it was created for.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class AuthoredTypeInspector
{
    /** The property {@link AddAuthorEditor} writes; a type declares it by carrying {@code jcr:lastModifiedBy}. */
    private static final String LAST_AUTHOR_PROPERTY = "jcr:lastModifiedBy";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String MIXIN_TYPES = "jcr:mixinTypes";

    /** Holds the full, transitively expanded set of supertypes of a registered node type. */
    private static final String SUPERTYPES = "rep:supertypes";

    private final NodeState registry;

    private final Map<String, Boolean> writable = new HashMap<>();

    /**
     * Basic constructor.
     *
     * @param root the repository root state holding the node type registry
     */
    AuthoredTypeInspector(final NodeState root)
    {
        this.registry = root.getChildNode("jcr:system").getChildNode("jcr:nodeTypes");
    }

    /**
     * Checks whether {@code auth:lastAuthor} may be stored on the given node.
     *
     * @param node the node to check
     * @return {@code true} if one of the node's types declares the property
     */
    boolean canStoreAuthor(final NodeState node)
    {
        return anyTypeMatches(node.getProperty(PRIMARY_TYPE)) || anyTypeMatches(node.getProperty(MIXIN_TYPES));
    }

    private boolean anyTypeMatches(final PropertyState types)
    {
        if (types == null) {
            return false;
        }
        final Predicate<String> verdict = this::isWritableType;
        if (types.isArray()) {
            return StreamSupport.stream(types.getValue(Type.NAMES).spliterator(), false).anyMatch(verdict);
        }
        return verdict.test(types.getValue(Type.NAME));
    }

    private boolean isWritableType(final String type)
    {
        return this.writable.computeIfAbsent(type, this::computeWritableType);
    }

    private boolean computeWritableType(final String type)
    {
        final NodeState definition = this.registry.getChildNode(type);
        if (!definition.exists()) {
            return false;
        }
        if (accepts(definition)) {
            return true;
        }
        final PropertyState supertypes = definition.getProperty(SUPERTYPES);
        return supertypes != null && StreamSupport.stream(supertypes.getValue(Type.NAMES).spliterator(), false)
            .anyMatch(supertype -> accepts(this.registry.getChildNode(supertype)));
    }

    private boolean accepts(final NodeState definition)
    {
        return definition.getChildNode("rep:namedPropertyDefinitions").hasChildNode(LAST_AUTHOR_PROPERTY);
    }
}
