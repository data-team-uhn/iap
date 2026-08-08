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
package io.uhndata.iap.categories.models;

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.AutoDocumentable;
import io.uhndata.iap.entities.models.EntityHomepage;

/**
 * A Sling Model wrapping a {@code cat:CategoriesHomepage} node, the root container of the {@code /Categories} tree.
 *
 * <p>The tree documents itself through the generic autodoc endpoints, {@code /Categories.doc.md} and
 * {@code /Categories.doc.json}: a flat catalogue of only the live leaf categories that submissions may currently
 * be filed under. A retired category is excluded together with its whole subtree, since
 * submissions may not be filed under any of its descendants either. The primary consumer is AI-assisted
 * categorization, which builds its prompt from the served descriptions; the heading and introduction can be
 * reworded without code through the {@code title} and {@code description} properties of the {@code iap:Documented}
 * mixin.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = CategoriesHomepage.RESOURCE_TYPE,
    adapters = { CategoriesHomepage.class, AutoDocumentable.class },
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class CategoriesHomepage extends EntityHomepage implements AutoDocumentable
{
    /** The {@code sling:resourceType} of a {@code cat:CategoriesHomepage} node. */
    public static final String RESOURCE_TYPE = "cat/CategoriesHomepage";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String description;

    /**
     * The top-level categories of the tree, in their stored order.
     *
     * @return a list of categories, empty if none
     */
    @NotNull
    public List<Category> getCategories()
    {
        return this.getChildren(Category.RESOURCE_TYPE, Category.class);
    }

    @Override
    @NotNull
    public String getDocumentationTitle()
    {
        return this.title;
    }

    @Override
    @Nullable
    public String getDocumentationIntro()
    {
        return this.description;
    }

    @Override
    @NotNull
    public List<Category> getDocumentedItems()
    {
        final List<Category> leaves = new ArrayList<>();
        collectLiveLeaves(getCategories(), leaves);
        return leaves;
    }

    /**
     * Depth-first traversal of the category tree, collecting every live leaf category. A retired category prunes
     * its whole subtree.
     *
     * @param categories the categories to examine, together with their descendants
     * @param leaves collects the live leaf categories
     */
    private void collectLiveLeaves(final List<Category> categories, final List<Category> leaves)
    {
        for (final Category category : categories) {
            if (category.isRetired()) {
                continue;
            }
            if (category.isLeaf()) {
                leaves.add(category);
            } else {
                collectLiveLeaves(category.getSubcategories(), leaves);
            }
        }
    }
}
