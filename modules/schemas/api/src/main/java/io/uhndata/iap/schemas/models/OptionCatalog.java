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
package io.uhndata.iap.schemas.models;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.models.annotations.Model;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.AutoDocumentable;
import io.uhndata.iap.autodoc.api.DocumentedItem;
import io.uhndata.iap.content.models.Content;

/**
 * The answers a choice question offers when they live at a content path rather than as child nodes: the live
 * items of that path, each with a value, a label and a description.
 *
 * <p>A path that documents itself ({@link AutoDocumentable}) is read as that catalogue, so {@code /Categories}
 * yields the live leaves an administrator has arranged, retired branches left out. A path that does not is
 * walked for labeled leaves, so a folder of options still works.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class OptionCatalog
{
    private OptionCatalog()
    {
        // Utility
    }

    /**
     * The options at a content path, in the order the catalogue or the tree arranges them.
     *
     * @param resolver how to reach the path
     * @param path an absolute repository path, e.g. {@code /Categories}
     * @return the options; empty when the path is missing or holds nothing that can be chosen
     */
    @NotNull
    public static List<OfferedOption> read(@NotNull final ResourceResolver resolver, @Nullable final String path)
    {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        final Resource root = resolver.getResource(path);
        if (root == null) {
            return List.of();
        }
        final AutoDocumentable catalog = getCatalog(root);
        return catalog != null ? fromDocumented(catalog) : fromLeaves(root);
    }

    /**
     * The catalogue the path documents, or {@code null} when it is a plain folder. Sling Models falls back to the
     * first implementation of an adapter when no resource type matches, so the model's own type is checked too.
     */
    private static AutoDocumentable getCatalog(final Resource root)
    {
        final AutoDocumentable catalog = root.adaptTo(AutoDocumentable.class);
        final Model model = catalog == null ? null : catalog.getClass().getAnnotation(Model.class);
        if (model == null || Arrays.stream(model.resourceType()).noneMatch(root::isResourceType)) {
            return null;
        }
        return catalog;
    }

    /**
     * The documented items as options. The value is the item's path when it is content, otherwise its name,
     * so a category is stored as {@code /Categories/…} and a name-only item as itself.
     */
    private static List<OfferedOption> fromDocumented(final AutoDocumentable catalog)
    {
        final List<OfferedOption> options = new ArrayList<>();
        for (final DocumentedItem item : catalog.getDocumentedItems()) {
            final String value = item instanceof Content content ? content.getPath() : item.getName();
            final String description = Objects.requireNonNullElse(item.getDescription(), "");
            options.add(new OfferedOption(value, item.getDocumentationLabel(), description));
        }
        return List.copyOf(options);
    }

    /**
     * Labeled leaves under the path, depth-first. A node with labeled children is a grouping, not a choice.
     */
    private static List<OfferedOption> fromLeaves(final Resource root)
    {
        final List<OfferedOption> options = new ArrayList<>();
        collectLeaves(root, true, options);
        return List.copyOf(options);
    }

    private static void collectLeaves(final Resource node, final boolean root,
        final List<OfferedOption> options)
    {
        final List<Resource> labeled = labeledChildren(node);
        if (!labeled.isEmpty()) {
            labeled.forEach(child -> collectLeaves(child, false, options));
            return;
        }
        if (!root) {
            final String label = labelOf(node);
            if (label != null) {
                options.add(new OfferedOption(node.getPath(), label, descriptionOf(node)));
            }
        }
    }

    private static List<Resource> labeledChildren(final Resource node)
    {
        final List<Resource> labeled = new ArrayList<>();
        node.getChildren().forEach(child -> {
            if (labelOf(child) != null) {
                labeled.add(child);
            }
        });
        return labeled;
    }

    private static String labelOf(final Resource node)
    {
        final String label = node.getValueMap().get("label", String.class);
        return label == null || label.isBlank() ? null : label;
    }

    private static String descriptionOf(final Resource node)
    {
        final String description = node.getValueMap().get("description", String.class);
        return description == null ? "" : description;
    }
}
