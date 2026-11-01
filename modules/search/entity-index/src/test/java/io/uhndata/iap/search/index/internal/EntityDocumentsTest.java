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
package io.uhndata.iap.search.index.internal;

import java.util.List;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.lucene.document.Document;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link EntityDocuments}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EntityDocumentsTest
{
    private static final String ENTITY_PATH = "/Requests/ab/request";

    private final SlingContext context = TestRepository.newContext();

    private Node entity;

    private List<Document> block;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        final Node bucket = TestRepository.session(this.context).getRootNode().addNode("Requests").addNode("ab");
        this.entity = bucket.addNode("request", "test:Request");
        this.entity.addMixin("test:Flagged");
        this.entity.setProperty("title", "A long weekend");
        this.entity.addNode("days", "test:Answer").setProperty("value", 3L);
        this.entity.addNode("section").addNode("reason", "test:Answer").setProperty("value", "A wedding");
        this.entity.addNode("approval", "test:Review");
        this.entity.addNode("aside", "test:Note");
        this.block = new EntityDocuments(Set.of("test:Answer", "test:Review")).of(this.entity);
    }

    @Test
    void putsTheIndexedDescendantsAtAnyDepthFirstAndTheEntityLast()
    {
        assertEquals(List.of(ENTITY_PATH + "/days", ENTITY_PATH + "/section/reason", ENTITY_PATH + "/approval",
            ENTITY_PATH), this.block.stream().map(document -> document.get(IndexFields.PATH_FIELD)).toList());
    }

    @Test
    void marksEachDocumentWithItsEntity()
    {
        this.block.forEach(document -> assertEquals(ENTITY_PATH, document.get(IndexFields.ENTITY_FIELD)));
    }

    @Test
    void tellsTheEntityFromItsDescendants()
    {
        assertEquals(List.of(IndexFields.DESCENDANT_KIND, IndexFields.DESCENDANT_KIND, IndexFields.DESCENDANT_KIND,
            IndexFields.ENTITY_KIND), kinds(this.block));
    }

    @Test
    void recordsEveryTypeANodeIs()
    {
        assertEquals(Set.of("test:Request", "test:Entity", "nt:unstructured", "nt:base", "mix:referenceable",
            "test:Flagged"), Set.of(entityDocument().getValues(IndexFields.TYPES_FIELD)));
    }

    @Test
    void indexesEachNodesOwnProperties()
    {
        assertEquals("A long weekend", entityDocument().get("title|String"));
        assertNull(entityDocument().get("value|String"));
        assertEquals("A wedding", this.block.get(1).get("value|String"));
    }

    @Test
    void indexesAnEntityWithNoDescendantsAsItsOwnDocument() throws RepositoryException
    {
        final List<Document> alone = new EntityDocuments(Set.of("test:Missing")).of(this.entity);

        assertEquals(List.of(IndexFields.ENTITY_KIND), kinds(alone));
    }

    private static List<String> kinds(final List<Document> documents)
    {
        return documents.stream().map(document -> document.get(IndexFields.KIND_FIELD)).toList();
    }

    private Document entityDocument()
    {
        return this.block.get(this.block.size() - 1);
    }
}
