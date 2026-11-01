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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.StoredField;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.search.api.Filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link FilterQueries}. Each expectation is what a JCR query on Oak was measured to return for the
 * same values, and the values are chosen so that comparing by the wrong rule would find something else.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FilterQueriesTest
{
    private static final String VALUE = "v";

    /** 2026-11-01T00:00:00Z, which is 2026-10-31T20:00 in Toronto. */
    private static final long INSTANT = 1_793_491_200_000L;

    private final SlingContext context = TestRepository.newContext();

    private Session session;

    private Node holders;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.session = TestRepository.session(this.context);
        this.holders = this.session.getRootNode().addNode("holders");
    }

    @Test
    void comparesAWholeNumberAsANumberWhenTheValueConverts() throws Exception
    {
        holder("three").setProperty(VALUE, 3L);
        holder("ten").setProperty(VALUE, 10L);

        // As text, neither "3" nor "10" is above "9"
        assertEquals(Set.of("ten"), matching(">", "9"));
    }

    @Test
    void comparesAsTextWhenTheValueDoesNotConvert() throws Exception
    {
        holder("three").setProperty(VALUE, 3L);
        holder("nine").setProperty(VALUE, 9L);
        holder("ten").setProperty(VALUE, 10L);

        assertEquals(Set.of("three", "nine"), matching(">", "2026-10-31"));
    }

    @Test
    void findsAWholeNumberOnlyByAValueThatConvertsExactly() throws Exception
    {
        holder("whole").setProperty(VALUE, 3L);
        holder("fraction").setProperty(VALUE, 3.0d);

        assertEquals(Set.of("fraction"), matching("=", "3.0"));
        assertEquals(Set.of("whole", "fraction"), matching("=", "3"));
    }

    @Test
    void comparesWholeNumbersAtEachBound() throws Exception
    {
        holder("three").setProperty(VALUE, 3L);
        holder("nine").setProperty(VALUE, 9L);

        assertEquals(Set.of("three"), matching("<", "9"));
        assertEquals(Set.of("three", "nine"), matching("<=", "9"));
        assertEquals(Set.of("nine"), matching(">=", "9"));
    }

    @Test
    void comparesNumbersAtEachBound() throws Exception
    {
        holder("low").setProperty(VALUE, 2.5d);
        holder("high").setProperty(VALUE, 3.0d);

        assertEquals(Set.of("low"), matching("<", "3"));
        assertEquals(Set.of("low", "high"), matching("<=", "3"));
        assertEquals(Set.of("high"), matching(">", "2.5"));
        assertEquals(Set.of("low", "high"), matching(">=", "2.5"));
    }

    @Test
    void comparesDatesByTheInstantTheyName() throws Exception
    {
        holder("utc").setProperty(VALUE, at("UTC"));
        holder("toronto").setProperty(VALUE, at("America/Toronto"));

        assertEquals(Set.of("utc", "toronto"), matching("=", "2026-10-31T20:00:00.000-04:00"));
        assertEquals(Set.of("utc", "toronto"), matching(">", "2026-10-31T00:00:00.000Z"));
        // A day alone is not a date to the repository, so it is compared as text
        assertEquals(Set.of(), matching("=", "2026-11-01"));
    }

    @Test
    void readsAnyValueAsYesOrNo() throws Exception
    {
        holder("yes").setProperty(VALUE, true);
        holder("no").setProperty(VALUE, false);

        assertEquals(Set.of("no"), matching("=", "yes"));
        assertEquals(Set.of("yes"), matching(">", "zzz"));
    }

    @Test
    void comparesTextAsText() throws Exception
    {
        holder("lower").setProperty(VALUE, "abc");
        holder("upper").setProperty(VALUE, "Abc");
        holder("digit").setProperty(VALUE, "9");

        assertEquals(Set.of("lower"), matching("=", "abc"));
        assertEquals(Set.of("lower"), matching(">=", "abc"));
        assertEquals(Set.of("upper", "digit"), matching("<", "abc"));
        assertEquals(Set.of("upper", "digit"), matching("<=", "Abc"));
    }

    @Test
    void matchesAPatternAgainstTheTextOfAnyValue() throws Exception
    {
        holder("three").setProperty(VALUE, 3L);
        holder("ten").setProperty(VALUE, 10L);
        holder("text").setProperty(VALUE, "abc");
        holder("none");

        assertEquals(Set.of("ten"), matching("LIKE", "1%"));
        assertEquals(Set.of("text"), matching("LIKE", "a_c"));
        assertEquals(Set.of("three", "text", "none"), matching("NOT LIKE", "1%"));
    }

    @Test
    void matchesAPatternWhateverTheCase() throws Exception
    {
        holder("lower").setProperty(VALUE, "abc");
        holder("upper").setProperty(VALUE, "Abc");
        holder("none");

        assertEquals(Set.of("lower", "upper"), matching("ILIKE", "ABC"));
        assertEquals(Set.of("none"), matching("NOT ILIKE", "a%"));
    }

    @Test
    void excludesWhateverHasAnEqualValue() throws Exception
    {
        holder("both").setProperty(VALUE, new String[] { "3", "4" });
        holder("nine").setProperty(VALUE, 9L);
        holder("none");

        assertEquals(Set.of("nine", "none"), matching("<>", "3"));
    }

    @Test
    void tellsASetPropertyFromAMissingOne() throws Exception
    {
        holder("set").setProperty(VALUE, 3L);
        holder("none");

        assertEquals(Set.of("none"), matching("IS NULL", null));
        assertEquals(Set.of("set"), matching("IS NOT NULL", null));
    }

    @Test
    void comparesAnAbsentValueAsEmptyText() throws Exception
    {
        holder("empty").setProperty(VALUE, "");
        holder("text").setProperty(VALUE, "abc");

        assertEquals(Set.of("empty"), matching("=", null));
    }

    private Node holder(final String name) throws RepositoryException
    {
        return this.holders.addNode(name, "test:Answer");
    }

    private Calendar at(final String zone)
    {
        final Calendar date = new GregorianCalendar(TimeZone.getTimeZone(zone));
        date.setTimeInMillis(INSTANT);
        return date;
    }

    /**
     * The holders a condition on their value finds, once indexed.
     *
     * @param comparator the condition's comparator
     * @param value the value compared against
     * @return the names of the holders found
     * @throws Exception if the holders cannot be indexed or searched
     */
    private Set<String> matching(final String comparator, final String value) throws Exception
    {
        final List<Document> documents = new ArrayList<>();
        for (final NodeIterator nodes = this.holders.getNodes(); nodes.hasNext();) {
            documents.add(document(nodes.nextNode()));
        }
        try (SearchedIndex index = SearchedIndex.of(documents)) {
            final FilterQueries conditions =
                new FilterQueries(StoredTypes.of(index.reader()), this.session.getValueFactory());
            return Set.copyOf(index.paths(conditions.of(new Filter(VALUE, comparator, value))));
        }
    }

    private static Document document(final Node holder) throws RepositoryException
    {
        final Document document = new Document();
        document.add(new StoredField(IndexFields.PATH_FIELD, holder.getName()));
        for (final PropertyIterator properties = holder.getProperties(); properties.hasNext();) {
            PropertyFields.add(properties.nextProperty(), document);
        }
        return document;
    }
}
