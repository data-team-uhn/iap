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

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;

import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.search.api.DescendantFilters;
import io.uhndata.iap.search.api.EntityListing;
import io.uhndata.iap.search.api.Filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Unit tests for {@link ListingSearch}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ListingSearchTest
{
    private static final String HOMEPAGE = "/Requests";

    private static final String REQUEST = "test:Request";

    private static final String ANSWER = "test:Answer";

    private static final String FIRST = HOMEPAGE + "/ab/first";

    private static final String SECOND = HOMEPAGE + "/ab/second";

    private static final String THIRD = HOMEPAGE + "/cd/third";

    private static final String TITLE = "title";

    private final SlingContext context = TestRepository.newContext();

    private final Set<String> hidden = new HashSet<>();

    private Session session;

    private EntityIndex index;

    private ListingSearch search;

    @BeforeEach
    void setUp() throws Exception
    {
        this.session = TestRepository.session(this.context);
        final Node first = request(FIRST, "Beta", 2L);
        answer(first, "days", "q1", 3L);
        answer(first, "reason", "q2", "A wedding");
        final Node second = request(SECOND, "Alpha", 10L);
        answer(second, "days", "q1", 3L);
        second.addNode("approval", "test:Review").setProperty("decision", "approved");
        final Node third = request(THIRD, null, 1L);
        answer(third.addNode("section"), "days", "q1", 5L);
        this.index = new EntityIndex(new ByteBuffersDirectory());
        this.search = new ListingSearch(this.index, HOMEPAGE, REQUEST, Set.of(ANSWER, "test:Review"));
        reindex();
    }

    @AfterEach
    void tearDown() throws IOException
    {
        this.index.close();
    }

    @Test
    void listsTheEntitiesItsConditionsMatch() throws Exception
    {
        assertEquals(List.of(FIRST), found(listing(List.of(new Filter(TITLE, "=", "Beta")), List.of())));
        assertEquals(List.of(FIRST, SECOND), found(listing(List.of(new Filter("rank", "<", "9", "g"),
            new Filter(TITLE, "=", "Alpha", "g"), new Filter(TITLE, "IS NOT NULL", null)), List.of())));
    }

    @Test
    void findsNothingWhenNothingMatches() throws Exception
    {
        assertEquals(List.of(), found(listing(List.of(new Filter(TITLE, "=", "Gamma")), List.of())));
    }

    @Test
    void asksEachFamilyOfADescendantOfItsOwn() throws Exception
    {
        final DescendantFilters threeDays = family(ANSWER, "q1", "=", "3");
        final DescendantFilters aWedding = family(ANSWER, "q2", "=", "A wedding");

        assertEquals(List.of(FIRST, SECOND), found(listing(List.of(), List.of(threeDays))));
        assertEquals(List.of(FIRST), found(listing(List.of(), List.of(threeDays, aWedding))));
    }

    @Test
    void findsDescendantsAtAnyDepth() throws Exception
    {
        assertEquals(List.of(THIRD), found(listing(List.of(), List.of(family(ANSWER, "q1", ">", "4")))));
    }

    @Test
    void ordersTheEntitiesWithThoseLackingThePropertyFirst() throws Exception
    {
        assertEquals(List.of(THIRD, SECOND, FIRST), found(sortedBy(TITLE, false)));
        assertEquals(List.of(FIRST, SECOND, THIRD), found(sortedBy(TITLE, true)));
    }

    @Test
    void ordersWholeNumbersAsNumbers() throws Exception
    {
        assertEquals(List.of(THIRD, FIRST, SECOND), found(sortedBy("rank", false)));
    }

    @Test
    void ordersNumbersAndDatesAsSuch() throws Exception
    {
        this.session.getNode(FIRST).setProperty("ratio", 0.5d);
        this.session.getNode(SECOND).setProperty("ratio", -2.5d);
        this.session.getNode(FIRST).setProperty("due", this.session.getValueFactory()
            .createValue("2026-12-01T00:00:00.000Z", PropertyType.DATE));
        this.session.getNode(SECOND).setProperty("due", this.session.getValueFactory()
            .createValue("2026-11-01T00:00:00.000Z", PropertyType.DATE));
        reindex();

        assertEquals(List.of(THIRD, SECOND, FIRST), found(sortedBy("ratio", false)));
        assertEquals(List.of(FIRST, SECOND, THIRD), found(sortedBy("due", true)));
    }

    @Test
    void leavesThemInIndexOrderByAPropertyNoneHas() throws Exception
    {
        assertEquals(Set.of(FIRST, SECOND, THIRD), Set.copyOf(found(sortedBy("absent", false))));
        // Only their descendants have it
        assertEquals(Set.of(FIRST, SECOND, THIRD), Set.copyOf(found(sortedBy("question", false))));
    }

    @Test
    void leavesOutWhatTheAskerCannotRead() throws Exception
    {
        this.hidden.add(SECOND);

        assertEquals(List.of(FIRST, THIRD), found(sortedBy("rank", true)));
    }

    @Test
    void leavesOutAnEntityMatchedOnlyThroughDescendantsTheAskerCannotRead() throws Exception
    {
        this.hidden.add(FIRST + "/days");
        final EntityListing threeDays = listing(List.of(), List.of(family(ANSWER, "q1", "=", "3")));

        assertEquals(List.of(SECOND), found(threeDays));
    }

    @Test
    void servesAListingOfItsEntities()
    {
        assertTrue(this.search.canServe(listing(List.of(new Filter(TITLE, "=", "Beta")),
            List.of(family(ANSWER, "q1", "=", "3")))));
    }

    @Test
    void declinesAListingOfOtherEntities()
    {
        assertFalse(this.search.canServe(new EntityListing("/Elsewhere", REQUEST, List.of(), List.of(), null,
            TITLE, false)));
        assertFalse(this.search.canServe(new EntityListing(HOMEPAGE, "test:Entity", List.of(), List.of(), null,
            TITLE, false)));
    }

    @Test
    void declinesAFullTextSearch()
    {
        assertFalse(this.search.canServe(new EntityListing(HOMEPAGE, REQUEST, List.of(), List.of(), "wedding",
            TITLE, false)));
        assertTrue(this.search.canServe(new EntityListing(HOMEPAGE, REQUEST, List.of(), List.of(), " ",
            TITLE, false)));
    }

    @Test
    void declinesConditionsOnDescendantsItDoesNotIndex()
    {
        assertFalse(this.search.canServe(listing(List.of(), List.of(family("test:Note", "q1", "=", "3")))));
    }

    @Test
    void declinesAConditionOnTextItHoldsOnlyInPart() throws Exception
    {
        this.session.getNode(FIRST).setProperty("story", "a".repeat(5000));
        reindex();

        assertFalse(this.search.canServe(listing(List.of(new Filter("story", "LIKE", "a%")), List.of())));
    }

    @Test
    void declinesAConditionOnADecimal() throws Exception
    {
        this.session.getNode(FIRST).setProperty("amount", new BigDecimal("1.5"));
        reindex();

        assertFalse(this.search.canServe(listing(List.of(new Filter("amount", ">", "1")), List.of())));
    }

    @Test
    void declinesAnOrderTheRepositoryGivesDifferently() throws Exception
    {
        this.session.getNode(FIRST).setProperty("tags", new String[] { "b", "a" });
        this.session.getNode(FIRST).setProperty("mixed", 1L);
        this.session.getNode(SECOND).setProperty("mixed", "one");
        reindex();

        assertFalse(this.search.canServe(sortedBy("tags", false)));
        assertFalse(this.search.canServe(sortedBy("mixed", false)));
    }

    @Test
    void declinesWhatItCannotRead() throws Exception
    {
        final EntityIndex unreadable = Mockito.mock(EntityIndex.class);
        Mockito.when(unreadable.search(any())).thenThrow(new IOException("Gone"));

        assertFalse(new ListingSearch(unreadable, HOMEPAGE, REQUEST, Set.of(ANSWER))
            .canServe(sortedBy(TITLE, false)));
    }

    @Test
    void failsAListingWhoseOrderItCanNoLongerGive() throws Exception
    {
        this.session.getNode(FIRST).setProperty("tags", new String[] { "b", "a" });
        reindex();

        assertThrows(IOException.class, () -> found(sortedBy("tags", false)));
    }

    @Test
    void failsWithoutTheRepositorysRulesForValues() throws Exception
    {
        final Session noValues = Mockito.mock(Session.class);
        Mockito.when(noValues.getValueFactory()).thenThrow(new UnsupportedRepositoryOperationException());

        assertThrows(IOException.class, () -> this.search.find(sortedBy(TITLE, false), resolver(noValues)));
    }

    @Test
    void failsOnAValueItCannotRead() throws Exception
    {
        final Value unreadable = Mockito.mock(Value.class);
        Mockito.when(unreadable.getString()).thenThrow(new RepositoryException("Unreadable"));
        final ValueFactory values = Mockito.mock(ValueFactory.class);
        Mockito.when(values.createValue(anyString(), anyInt())).thenReturn(unreadable);
        final Session converting = Mockito.mock(Session.class);
        Mockito.when(converting.getValueFactory()).thenReturn(values);

        assertThrows(IOException.class, () -> this.search.find(
            listing(List.of(new Filter(TITLE, "=", "Beta")), List.of()), resolver(converting)));
    }

    private Node request(final String path, final String title, final long rank) throws RepositoryException
    {
        Node node = this.session.getRootNode();
        final String[] names = path.substring(1).split("/");
        for (int i = 0; i < names.length - 1; ++i) {
            node = node.hasNode(names[i]) ? node.getNode(names[i]) : node.addNode(names[i]);
        }
        final Node request = node.addNode(names[names.length - 1], REQUEST);
        if (title != null) {
            request.setProperty(TITLE, title);
        }
        request.setProperty("rank", rank);
        return request;
    }

    private static void answer(final Node parent, final String name, final String question, final Object value)
        throws RepositoryException
    {
        final Node answer = parent.addNode(name, ANSWER);
        answer.setProperty("question", question);
        if (value instanceof Long number) {
            answer.setProperty("value", number);
        } else {
            answer.setProperty("value", (String) value);
        }
    }

    private void reindex() throws Exception
    {
        final EntityDocuments documents = new EntityDocuments(Set.of(ANSWER, "test:Review"));
        for (final String path : List.of(FIRST, SECOND, THIRD)) {
            this.index.replace(path, documents.of(this.session.getNode(path)));
        }
    }

    private static DescendantFilters family(final String type, final String question, final String comparator,
        final String value)
    {
        return new DescendantFilters(type, List.of(new Filter("question", "=", question),
            new Filter("value", comparator, value)));
    }

    private static EntityListing listing(final List<Filter> filters, final List<DescendantFilters> descendants)
    {
        return new EntityListing(HOMEPAGE, REQUEST, filters, descendants, null, "rank", false);
    }

    private static EntityListing sortedBy(final String property, final boolean descending)
    {
        return new EntityListing(HOMEPAGE, REQUEST, List.of(), List.of(), null, property, descending);
    }

    private List<String> found(final EntityListing listing) throws IOException
    {
        final List<String> paths = new ArrayList<>();
        this.search.find(listing, resolver(this.session)).forEachRemaining(paths::add);
        return paths;
    }

    private ResourceResolver resolver(final Session reading)
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(reading);
        Mockito.when(resolver.getResource(anyString())).thenAnswer(invocation ->
            this.hidden.contains(invocation.<String>getArgument(0)) ? null : Mockito.mock(Resource.class));
        return resolver;
    }
}
