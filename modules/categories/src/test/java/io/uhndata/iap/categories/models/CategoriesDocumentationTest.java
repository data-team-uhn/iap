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

import java.util.List;
import java.util.Map;

import jakarta.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.autodoc.api.AutoDocumentable;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityHomepage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the self-served documentation of the category tree: {@link CategoriesHomepage}'s
 * {@link AutoDocumentable} side and {@link Category}'s {@code DocumentedItem} side.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CategoriesDocumentationTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String LEAF_UUID = "11111111-2222-3333-4444-555555555555";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityHomepage.class,
            CategoriesHomepage.class, Category.class);
    }

    /**
     * Builds a tree exercising every documentation rule: a branch category whose leaves are documented (the branch
     * itself is not), a live top-level leaf, a retired leaf, a retired branch with a live leaf under it (pruned
     * entirely), and non-category children (ignored).
     *
     * @return the homepage model of the created tree
     */
    private CategoriesHomepage createTree()
    {
        final Resource root =
            this.context.create().resource("/Categories", SLING_RESOURCE_TYPE, CategoriesHomepage.RESOURCE_TYPE);
        this.context.create().resource("/Categories/Retrospective", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Retrospective studies",
            "description", "Studies using existing data or specimens."));
        this.context.create().resource("/Categories/Retrospective/RetrospectiveData", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Retrospective Data Studies",
            "description", "Chart reviews and analyses of previously collected data.",
            "jcr:uuid", LEAF_UUID));
        // No label, no description, no uuid: documented with the node name as label, without description and id
        this.context.create().resource("/Categories/Retrospective/RetrospectiveBiospecimen",
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE);
        // A non-category child does not stop its parent from being a leaf
        this.context.create().resource("/Categories/Retrospective/RetrospectiveBiospecimen/attachment",
            SLING_RESOURCE_TYPE, "iap/Content");
        this.context.create().resource("/Categories/Quick", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Quick projects"));
        this.context.create().resource("/Categories/Paper", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Paper submissions",
            "retired", true));
        this.context.create().resource("/Categories/Legacy", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Legacy studies",
            "retired", true));
        this.context.create().resource("/Categories/Legacy/LegacyData", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Legacy Data Studies"));
        this.context.create().resource("/Categories/notes", SLING_RESOURCE_TYPE, "iap/Content");
        return root.adaptTo(CategoriesHomepage.class);
    }

    @Test
    void adaptsResourceToAutoDocumentable()
    {
        createTree();
        final AutoDocumentable documentation =
            this.context.resourceResolver().getResource("/Categories").adaptTo(AutoDocumentable.class);

        assertTrue(documentation instanceof CategoriesHomepage);
    }

    @Test
    void documentsOnlyLiveLeafCategories()
    {
        final List<Category> items = createTree().getDocumentedItems();

        assertEquals(List.of("RetrospectiveData", "RetrospectiveBiospecimen", "Quick"),
            items.stream().map(Category::getName).toList());
    }

    @Test
    void serializesTheCategoryDetails()
    {
        final JsonObject leaf = createTree().getDocumentedItems().get(0).toDocumentationJson();

        assertEquals("RetrospectiveData", leaf.getString("name"));
        assertEquals("Retrospective Data Studies", leaf.getString("label"));
        assertEquals("Chart reviews and analyses of previously collected data.", leaf.getString("description"));
        assertEquals("/Categories/Retrospective/RetrospectiveData", leaf.getString("path"));
        assertEquals(LEAF_UUID, leaf.getString("id"));
        assertFalse(leaf.containsKey("category"));
    }

    @Test
    void omitsMissingOptionalFieldsAndFallsBackToTheNameAsLabel()
    {
        final JsonObject leaf = createTree().getDocumentedItems().get(1).toDocumentationJson();

        assertEquals("RetrospectiveBiospecimen", leaf.getString("label"));
        assertFalse(leaf.containsKey("description"));
        assertFalse(leaf.containsKey("id"));
    }

    @Test
    void serializesTheWholeCatalogueWithDefaultHeadings()
    {
        final JsonObject catalogue = createTree().toDocumentationJson();

        assertEquals("Submission categories", catalogue.getString("title"));
        assertEquals("The categories a submission may currently be filed under.", catalogue.getString("description"));
        // The catalogue is deliberately flat: every leaf lands in the one default group
        assertEquals(3, catalogue.getJsonObject("items").getJsonArray("uncategorized").size());
    }

    @Test
    void headingsAreRewordableThroughTheDocumentedMixinProperties()
    {
        final Resource root = this.context.create().resource("/Categories", Map.of(
            SLING_RESOURCE_TYPE, CategoriesHomepage.RESOURCE_TYPE,
            "title", "Study categories",
            "description", "Pick the one leaf that fits best."));
        final CategoriesHomepage homepage = root.adaptTo(CategoriesHomepage.class);

        assertEquals("Study categories", homepage.getDocumentationTitle());
        assertEquals("Pick the one leaf that fits best.", homepage.getDocumentationIntro());
    }

    @Test
    void rendersTheCatalogueAsMarkdown()
    {
        final String markdown = createTree().toMarkdown();

        assertTrue(markdown.startsWith("# Submission categories\n"));
        // Only the leaves are rendered, flat: their labels are level 2 headings right under the title, with no
        // technical node name after the label, and the branches contribute no headings of their own
        assertTrue(markdown.contains("\n## Retrospective Data Studies\n"));
        assertFalse(markdown.contains("`RetrospectiveData`"));
        assertFalse(markdown.contains("Retrospective studies"));
        assertTrue(markdown.contains("\nChart reviews and analyses of previously collected data.\n"));
        // The pruned and retired categories are nowhere in the rendering
        assertFalse(markdown.contains("Legacy"));
        assertFalse(markdown.contains("Paper"));
    }

    @Test
    void documentsAnEmptyTreeAsAnEmptyCatalogue()
    {
        final Resource root = this.context.create().resource("/Categories",
            SLING_RESOURCE_TYPE, CategoriesHomepage.RESOURCE_TYPE);
        final CategoriesHomepage homepage = root.adaptTo(CategoriesHomepage.class);

        assertTrue(homepage.getDocumentedItems().isEmpty());
        assertTrue(homepage.toDocumentationJson().getJsonObject("items").isEmpty());
    }
}
