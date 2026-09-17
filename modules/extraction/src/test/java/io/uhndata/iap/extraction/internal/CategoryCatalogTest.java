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
package io.uhndata.iap.extraction.internal;

import java.util.List;
import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.categories.models.CategoriesHomepage;
import io.uhndata.iap.categories.models.Category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CategoryCatalog}: which categories a model is shown, how, and how its answer is read
 * back.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CategoryCatalogTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String LABEL = "label";

    private static final String DESCRIPTION = "description";

    private static final String DATA = "/Categories/Retrospective/Data";

    private static final String DATA_LABEL = "Retrospective Data Studies";

    private static final String BIOSPECIMEN = "/Categories/Retrospective/Biospecimen";

    private static final List<CategoryCatalog.Entry> ENTRIES = List.of(
        new CategoryCatalog.Entry(DATA, DATA_LABEL, "Chart reviews."),
        new CategoryCatalog.Entry(BIOSPECIMEN, "Retrospective Biospecimen Studies", "Banked\n   samples."));

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(CategoriesHomepage.class, Category.class);
    }

    private void createTree()
    {
        this.context.create().resource(CategoryCatalog.ROOT_PATH, Map.of(TYPE, CategoriesHomepage.RESOURCE_TYPE));
        this.context.create().resource("/Categories/Retrospective", Map.of(TYPE, Category.RESOURCE_TYPE,
            LABEL, "Retrospective studies", DESCRIPTION, "Existing data or specimens."));
        this.context.create().resource(DATA, Map.of(TYPE, Category.RESOURCE_TYPE,
            LABEL, DATA_LABEL, DESCRIPTION, "Chart reviews."));
        // No label and no description
        this.context.create().resource(BIOSPECIMEN, Map.of(TYPE, Category.RESOURCE_TYPE));
    }

    @Test
    void readsTheLeavesOfTheTreeInOrder()
    {
        createTree();

        final List<CategoryCatalog.Entry> entries = CategoryCatalog.read(this.context.resourceResolver());

        assertEquals(List.of(DATA, BIOSPECIMEN), entries.stream().map(CategoryCatalog.Entry::path).toList(),
            "a category with subcategories is not itself a choice");
        assertEquals(DATA_LABEL, entries.get(0).label());
        assertEquals("Chart reviews.", entries.get(0).description());
    }

    @Test
    void namesALeafWithNoLabelByItsNodeAndGivesItNoDescription()
    {
        createTree();

        final CategoryCatalog.Entry bare = CategoryCatalog.read(this.context.resourceResolver()).get(1);

        assertEquals("Biospecimen", bare.label());
        assertEquals("", bare.description());
    }

    @Test
    void readsNothingWhenThereIsNoTree()
    {
        assertTrue(CategoryCatalog.read(this.context.resourceResolver()).isEmpty());
    }

    @Test
    void readsNothingWhenTheTreeHoldsNoCategories()
    {
        this.context.create().resource(CategoryCatalog.ROOT_PATH, Map.of(TYPE, CategoriesHomepage.RESOURCE_TYPE));

        assertTrue(CategoryCatalog.read(this.context.resourceResolver()).isEmpty());
    }

    @Test
    void describesOneCategoryPerLine()
    {
        assertEquals(CategoryCatalog.HEADER + "\n\n"
            + DATA + ": " + DATA_LABEL + " -- Chart reviews.\n"
            + BIOSPECIMEN + ": Retrospective Biospecimen Studies -- Banked samples.",
            CategoryCatalog.describe(ENTRIES), "a description is one line, whatever its shape in the tree");
    }

    @Test
    void leavesTheDescriptionOffALineThatHasNone()
    {
        final String block = CategoryCatalog.describe(List.of(new CategoryCatalog.Entry(DATA, "Data", "  ")));

        assertTrue(block.endsWith(DATA + ": Data"), block);
    }

    @Test
    void findsACategoryByItsPath()
    {
        assertEquals(BIOSPECIMEN, CategoryCatalog.find(ENTRIES, " " + BIOSPECIMEN + "\n").path(),
            "stray whitespace around the answer is not held against it");
    }

    @Test
    void findsACategoryByItsLabelWhenTheModelCopiedThatInstead()
    {
        assertEquals(DATA, CategoryCatalog.find(ENTRIES, "retrospective data studies").path());
    }

    @Test
    void findsNothingForAnAnswerThatNamesNoCategory()
    {
        assertNull(CategoryCatalog.find(ENTRIES, "/Categories/Prospective"));
        assertNull(CategoryCatalog.find(ENTRIES, "Prospective"));
        assertNull(CategoryCatalog.find(ENTRIES, "   "));
        assertNull(CategoryCatalog.find(ENTRIES, null));
    }
}
