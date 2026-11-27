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
package io.uhndata.iap.categories;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.json.JsonObject;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.testing.SystemWorkflowDefinitions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the shipped category workflows. Each admits administrators only and uses the generic content steps. Their
 * guards make up the category lifecycle: an open category is edited, moved, given subcategories or retired, and a
 * retired one is reopened while its parent is open. Either may be deleted.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CategoryWorkflowsTest
{
    private static final String HOMEPAGE = "cat/CategoriesHomepage";

    private static final String CATEGORY = "cat/Category";

    private static final String[] NONE = {};

    private static final String[] RETIRED = { "retired" };

    private static final Set<String> HANDLERS =
        Set.of("createContent", "updateContent", "moveContent", "addTag", "removeTag", "delete");

    private final SlingContext context = new SlingContext();

    private SystemWorkflowDefinitions shipped;

    @BeforeEach
    void setUp() throws IOException
    {
        this.shipped = SystemWorkflowDefinitions.loadIn(this.context, CategoryWorkflowsTest.class);
    }

    @Test
    void everyDefinitionIsAdministrativeAndUsesGenericSteps() throws IOException
    {
        assertEquals(List.of("createCategory", "createSubcategory", "deleteCategory", "moveCategory",
            "reopenCategory", "retireCategory", "updateCategory"), this.shipped.names());
        for (final String name : this.shipped.names()) {
            final String target = this.shipped.version(name).getString("targetResourceType");
            assertTrue(Set.of(HOMEPAGE, CATEGORY).contains(target), name);
            assertEquals(List.of("iap-administrators"), this.shipped.performers(name), name);
            assertTrue(HANDLERS.containsAll(this.shipped.handlers(name)), name);
        }
    }

    @Test
    void aTopLevelCategoryIsAddedOnTheHomepage()
    {
        assertEquals(Set.of("createCategory"), this.shipped.answering(HOMEPAGE, "create", NONE, NONE));
    }

    @Test
    void anOpenCategoryIsEditedMovedGivenSubcategoriesOrRetired()
    {
        assertEquals(Set.of("updateCategory"), this.shipped.answering(CATEGORY, "update", NONE, NONE));
        assertEquals(Set.of("moveCategory"), this.shipped.answering(CATEGORY, "move", NONE, NONE));
        assertEquals(Set.of("createSubcategory"), this.shipped.answering(CATEGORY, "create", NONE, NONE));
        assertEquals(Set.of("retireCategory"), this.shipped.answering(CATEGORY, "retire", NONE, NONE));
        assertEquals(Set.of(), this.shipped.answering(CATEGORY, "activate", NONE, NONE));
    }

    @Test
    void aRetiredCategoryIsStillEditedAndMoved()
    {
        assertEquals(Set.of("updateCategory"), this.shipped.answering(CATEGORY, "update", RETIRED, NONE));
        assertEquals(Set.of("moveCategory"), this.shipped.answering(CATEGORY, "move", RETIRED, RETIRED));
        assertEquals(Set.of(), this.shipped.answering(CATEGORY, "retire", RETIRED, NONE));
        // Retired through its parent, it is closed already
        assertEquals(Set.of(), this.shipped.answering(CATEGORY, "retire", NONE, RETIRED));
    }

    @Test
    void aRetiredCategoryIsReopenedWhileItsParentIsOpen()
    {
        assertEquals(Set.of("reopenCategory"), this.shipped.answering(CATEGORY, "activate", RETIRED, NONE));
        assertEquals(Set.of(), this.shipped.answering(CATEGORY, "activate", RETIRED, RETIRED));
        // Retired only through its parent, which reopening the parent undoes
        assertEquals(Set.of(), this.shipped.answering(CATEGORY, "activate", NONE, RETIRED));
    }

    @Test
    void aCategoryIsDeletedOpenOrRetired()
    {
        assertEquals(Set.of("deleteCategory"), this.shipped.answering(CATEGORY, "delete", NONE, NONE));
        assertEquals(Set.of("deleteCategory"), this.shipped.answering(CATEGORY, "delete", RETIRED, NONE));
        assertEquals(Set.of(), this.shipped.answering(HOMEPAGE, "delete", NONE, NONE));
    }

    @Test
    void aCategoryIsCreatedNamedAfterItsLabel() throws IOException
    {
        for (final String name : List.of("createCategory", "createSubcategory")) {
            final JsonObject create = this.shipped.version(name).getJsonObject("create");
            assertEquals("label", create.getJsonArray("nameFrom").getString(0), name);
            assertEquals("cat:Category",
                create.getJsonObject("types").getJsonObject("category").getString("nodeType"), name);
        }
    }

    @Test
    void aCategoryMovesOnlyWithinTheTree() throws IOException
    {
        assertEquals(HOMEPAGE, this.shipped.version("moveCategory").getJsonObject("move").getString("within"));
    }
}
