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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.models.annotations.Model;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.AutoDocumentable;
import io.uhndata.iap.autodoc.api.DocumentedItem;
import io.uhndata.iap.content.models.Content;

/**
 * Reads the answers a choice question offers when they live at a content path instead of in child nodes. Each
 * item gives a value, a label and a description.
 *
 * <p>A path that documents itself ({@link AutoDocumentable}) is read through its documented items, so
 * {@code /Categories} gives the live leaf categories, without the retired ones. A path inside such a catalogue,
 * such as {@code /Categories/Prospective}, gives the catalogue's items under that path. Any other path is walked
 * for labeled leaves, so a plain folder of options also works.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class OptionCatalog
{
    private static final String RESOURCE_TYPE_PROPERTY = "sling:resourceType";

    private OptionCatalog()
    {
        // Utility
    }

    /**
     * Returns the options at a content path, in the order the catalogue or the tree gives them.
     *
     * @param resolver the resolver used to read the path
     * @param path an absolute repository path, e.g. {@code /Categories}; may be {@code null}
     * @return the options; empty when the path is {@code null}, blank, missing, or holds nothing to choose
     */
    @NotNull
    static List<OfferedOption> read(@NotNull final ResourceResolver resolver, @Nullable final String path)
    {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        final Resource root = resolver.getResource(path);
        if (root == null) {
            return List.of();
        }
        final AutoDocumentable catalog = getCatalog(root);
        if (catalog != null) {
            return readDocumented(catalog);
        }
        for (Resource ancestor = root.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            final AutoDocumentable enclosing = getCatalog(ancestor);
            if (enclosing != null) {
                return readDocumentedUnder(enclosing, root.getPath() + "/");
            }
        }
        return readLeaves(root);
    }

    /**
     * The catalogue model of a resource, or {@code null} when it is a plain folder. When no resource type matches,
     * Sling Models falls back to the first model for the adapter, so the model's own resource type is checked too.
     * A node with no {@code sling:resourceType} of its own is never a catalogue, and is not adapted at all: that
     * would build a model for every folder above a path.
     */
    private static AutoDocumentable getCatalog(final Resource root)
    {
        if (!root.getValueMap().containsKey(RESOURCE_TYPE_PROPERTY)) {
            return null;
        }
        final AutoDocumentable catalog = root.adaptTo(AutoDocumentable.class);
        final Model model = catalog == null ? null : catalog.getClass().getAnnotation(Model.class);
        if (model == null || Arrays.stream(model.resourceType()).noneMatch(root::isResourceType)) {
            return null;
        }
        return catalog;
    }

    /** Every documented item of the catalogue, as an option. */
    private static List<OfferedOption> readDocumented(final AutoDocumentable catalog)
    {
        return catalog.getDocumentedItems().stream().map(OptionCatalog::toOption).toList();
    }

    /** The documented content items below a path prefix, as options. */
    private static List<OfferedOption> readDocumentedUnder(final AutoDocumentable catalog, final String prefix)
    {
        return catalog.getDocumentedItems().stream()
            .filter(item -> item instanceof final Content content && content.getPath().startsWith(prefix))
            .map(OptionCatalog::toOption)
            .toList();
    }

    /** A content item's value is its path, so a category is stored as {@code /Categories/...}; others use a name. */
    private static OfferedOption toOption(final DocumentedItem item)
    {
        final String value = item instanceof final Content content ? content.getPath() : item.getName();
        return new OfferedOption(value, item.getDocumentationLabel(),
            Objects.requireNonNullElse(item.getDescription(), ""));
    }

    /** The labeled leaves under the path, depth-first. The path itself is never a choice. */
    private static List<OfferedOption> readLeaves(final Resource root)
    {
        return getLabeledChildren(root).flatMap(OptionCatalog::getLeaves).toList();
    }

    /** A labeled node as a choice, or the choices below it: a node with labeled children is a group. */
    private static Stream<OfferedOption> getLeaves(final Resource node)
    {
        final List<Resource> labeled = getLabeledChildren(node).toList();
        if (!labeled.isEmpty()) {
            return labeled.stream().flatMap(OptionCatalog::getLeaves);
        }
        return Stream.of(new OfferedOption(node.getPath(), Objects.requireNonNull(getLabel(node),
            "Only labeled nodes are walked"), getDescription(node)));
    }

    private static Stream<Resource> getLabeledChildren(final Resource node)
    {
        return StreamSupport.stream(node.getChildren().spliterator(), false).filter(child -> getLabel(child) != null);
    }

    private static String getLabel(final Resource node)
    {
        final String label = node.getValueMap().get("label", String.class);
        return label == null || label.isBlank() ? null : label;
    }

    private static String getDescription(final Resource node)
    {
        final String description = node.getValueMap().get("description", String.class);
        return description == null ? "" : description;
    }
}
