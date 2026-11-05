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
 * The {@code /Categories} homepage, which holds the category tree.
 *
 * <p>It documents itself at {@code /Categories.doc.md} and {@code /Categories.doc.json}. The catalogue lists the
 * categories a submission may be filed under now: the leaves that are not retired. A retired category takes its whole
 * subtree out of the catalogue. The {@code title} and {@code description} properties hold the catalogue's heading
 * and introduction.</p>
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
     * The top-level categories, in their stored order.
     *
     * @return the categories directly under the homepage; empty if there are none
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
     * Adds the live leaves among the given categories and their descendants, depth first. The walk stops at a
     * retired category, since everything under it is retired too.
     *
     * @param categories the categories to walk, in display order
     * @param leaves the list the live leaves are added to, in display order
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
