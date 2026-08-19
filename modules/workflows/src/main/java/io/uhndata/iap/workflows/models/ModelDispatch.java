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
package io.uhndata.iap.workflows.models;

import java.util.Arrays;

import org.apache.sling.models.annotations.Model;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.content.models.Content;

/**
 * Tells a node that dispatched to the right model from one that was merely handed the nearest available one.
 *
 * <p>Both hierarchies here — the flow nodes and the {@code /WorkflowTypes} vocabulary — have abstract bases that are
 * the {@code resourceType} of no model, and residual {@code nt:unstructured} children throughout the tree make it
 * possible for a node to carry one of those as its own type anyway. Such a node matches no model by resource type,
 * and Sling then hands it to whichever implementation happens to sort first rather than rejecting it: a node
 * carrying {@code wf/FlowNode} silently arrives as an {@link Activity}. Leaving it out is the only answer that does
 * not invent a meaning for it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ModelDispatch
{
    private ModelDispatch()
    {
        // Utility class, not to be instantiated
    }

    /**
     * Whether a node really is of the kind whose model it arrived as. Arriving as a model says only which class was
     * built, so the node's own resource type has the last word — the same check {@link TaskInstance#getDefinition()}
     * makes before believing what it resolved.
     *
     * <p>The type to check against is read off the model that was built, rather than listed here: a list of the
     * abstract bases would be a second place to keep the hierarchy written down, and whatever was added without
     * being added to it would go on being mis-adapted, silently. The model registration already says which resource
     * type each model serves, and that is the one place that cannot fall out of step.</p>
     *
     * @param content the node to check, as the model it arrived as
     * @return {@code true} if the model that was built is the one registered for the node's own resource type, or
     *         for one it inherits from through {@code sling:resourceSuperType}
     */
    static boolean isConcrete(@NotNull final Content content)
    {
        return Arrays.stream(content.getClass().getAnnotationsByType(Model.class))
            .flatMap(model -> Arrays.stream(model.resourceType()))
            .anyMatch(content::isOfType);
    }
}
