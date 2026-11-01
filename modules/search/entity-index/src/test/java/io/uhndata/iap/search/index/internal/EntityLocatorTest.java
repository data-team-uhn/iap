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
import java.util.Optional;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EntityLocator}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EntityLocatorTest
{
    private static final String HOMEPAGE = "/Requests";

    private static final String FIRST = HOMEPAGE + "/ab/cd/first";

    private static final String SECOND = HOMEPAGE + "/ab/second";

    private final SlingContext context = TestRepository.newContext();

    private EntityLocator locator;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        final Session session = TestRepository.session(this.context);
        final Node buckets = session.getRootNode().addNode("Requests").addNode("ab");
        buckets.addNode("cd").addNode("first", "test:Request").addNode("days", "test:Answer");
        // An entity inside another is part of it, not one of the homepage's entities
        buckets.addNode("second", "test:Request").addNode("inner", "test:Request");
        // Above the homepage, a node named as if it were below it
        session.getRootNode().addNode("Requests2").addNode("third", "test:Request");
        this.locator = new EntityLocator(session, HOMEPAGE, "test:Request");
    }

    @Test
    void findsTheEntityADescendantIsPartOf() throws RepositoryException
    {
        assertEquals(Optional.of(FIRST), path(this.locator.enclosing(FIRST + "/days")));
    }

    @Test
    void findsTheEntityItself() throws RepositoryException
    {
        assertEquals(Optional.of(FIRST), path(this.locator.enclosing(FIRST)));
    }

    @Test
    void findsTheEntityAGoneDescendantWasPartOf() throws RepositoryException
    {
        assertEquals(Optional.of(FIRST), path(this.locator.enclosing(FIRST + "/gone/deeper")));
    }

    @Test
    void findsNoEntityForABucket() throws RepositoryException
    {
        assertEquals(Optional.empty(), this.locator.enclosing(HOMEPAGE + "/ab/cd"));
        assertEquals(Optional.empty(), this.locator.enclosing(HOMEPAGE + "/ab/gone"));
        assertEquals(Optional.empty(), this.locator.enclosing(HOMEPAGE));
    }

    @Test
    void findsNoEntityOutsideTheHomepage() throws RepositoryException
    {
        assertEquals(Optional.empty(), this.locator.enclosing("/Requests2/third"));
        assertEquals(Optional.empty(), this.locator.enclosing("/Gone/entirely"));
    }

    @Test
    void listsTheEntitiesDownThePrefixTree() throws RepositoryException
    {
        assertEquals(List.of(FIRST, SECOND), this.locator.under(HOMEPAGE));
        assertEquals(List.of(FIRST), this.locator.under(HOMEPAGE + "/ab/cd"));
    }

    @Test
    void listsNoEntitiesUnderWhatIsGone() throws RepositoryException
    {
        assertEquals(List.of(), this.locator.under(HOMEPAGE + "/gone"));
    }

    @Test
    void tellsAnEntityFromAnythingElse() throws RepositoryException
    {
        assertTrue(this.locator.isEntity(FIRST));
        assertFalse(this.locator.isEntity(HOMEPAGE + "/ab"));
        assertFalse(this.locator.isEntity(HOMEPAGE + "/gone"));
    }

    @Test
    void readsAnEntity() throws RepositoryException
    {
        assertEquals(SECOND, this.locator.get(SECOND).getPath());
    }

    private static Optional<String> path(final Optional<Node> node) throws RepositoryException
    {
        return node.isPresent() ? Optional.of(node.get().getPath()) : Optional.empty();
    }
}
