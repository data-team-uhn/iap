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
package io.uhndata.iap.search.internal;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.RowIterator;

import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import io.uhndata.iap.search.api.DescendantFilters;
import io.uhndata.iap.search.api.EntityListing;
import io.uhndata.iap.search.api.Filter;
import io.uhndata.iap.search.index.internal.EntityIndexer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the entity index answers listings exactly as the JCR query the pagination servlet would run, over
 * values of every type a property is stored in: the index replaces that query only where it agrees with it. Ties in
 * the order may come back in any order from either, so the answers are compared tie by tie.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EntityIndexEquivalenceTest
{
    private static final String HOMEPAGE = "/Requests";

    private static final String REQUEST = "eq:Request";

    private static final String ANSWER = "eq:Answer";

    private static final String REVIEW = "eq:Review";

    private static final String QUESTION = "question";

    private static final String VALUE = "value";

    private static final String RANK = "rank";

    private static final String TAGS = "tags";

    private static final List<String> COMPARATORS = List.of("=", "<>", "<", "<=", ">", ">=", "LIKE", "NOT LIKE",
        "ILIKE", "NOT ILIKE", "IS NULL", "IS NOT NULL");

    /** Values that convert to some stored types and not others, and patterns. */
    private static final List<String> VALUES = List.of("3", "3.0", "10", "-1", "abc", "Abc", "true", "yes",
        "2026-10-31", "2026-10-31T20:00:00.000-04:00", "1%", "a%", "%c", "_b%", "", "50\\%%");

    /** Title, rank, ratio, due, flag, tags and a property stored in a different type on each request. */
    private static final Object[][] REQUESTS = {
        { "ab/r01", "abc", 3L, 2.5d, date("2026-11-01T00:00:00.000Z"), true, tags("approved", "urgent"), 3L },
        // Due at the same instant as r01, in another zone
        { "ab/r02", "Abc", 9L, 3.0d, date("2026-10-31T20:00:00.000-04:00"), false, tags("draft"), 3.0d },
        { "ab/r03", "alpha", 10L, -1.5d, date("2026-12-01T09:30:00.000Z"), true, null, "3" },
        { "cd/ef/r04", "Beta", -1L, 0.0d, date("2025-01-01T00:00:00.000+05:30"), null, tags("rejected"), true },
        { "cd/ef/r05", "10", 100L, 10.0d, null, false, tags("a_c"), date("2026-10-31T20:00:00.000-04:00") },
        { "cd/ef/r06", "9", 0L, 3.5d, date("2026-11-02T00:00:00.000Z"), true, tags("50%off"), "abc" },
        { "cd/r07", "", null, null, date("2026-06-15T12:00:00.000Z"), false, tags(), 10L },
        { "cd/r08", "a_c", 33L, 33.3d, date("2026-11-01T00:00:00.001Z"), true, tags("urgent"), "Abc" },
        { "gh/r09", "50%off", 7L, 7.7d, date("2024-02-29T23:59:59.999Z"), null, tags("approved"), false },
        { "gh/r10", null, 12L, 1.25d, null, true, tags("urgent", "approved"), 10.0d },
        { "gh/r11", "2026-11-01", 4L, 4.4d, date("2026-11-01T00:00:00.000+01:00"), false, null, "2026-10-31" },
        { "gh/r12", "yes", 5L, 5.5d, date("2027-03-01T00:00:00.000Z"), true, tags("draft"), -1L },
    };

    /** Where an answer is below its request, the question it answers, and its value. */
    private static final Object[][] ANSWERS = {
        { "ab/r01", "days", "q1", 3L }, { "ab/r01", "details/why", "q2", "A wedding" },
        { "ab/r01", "details/paid", "q3", true }, { "ab/r01", "start", "q4", date("2026-11-02T00:00:00.000Z") },
        { "ab/r02", "days", "q1", 10L }, { "ab/r02", "why", "q2", "abc" }, { "ab/r02", "paid", "q3", false },
        { "ab/r02", "hours", "q5", 2.5d },
        { "ab/r03", "deep/er/days", "q1", -1L }, { "ab/r03", "why", "q2", "Abc" },
        { "cd/ef/r04", "days", "q1", 3L }, { "cd/ef/r04", "days2", "q1", 9L },
        { "cd/ef/r04", "start", "q4", date("2026-10-31T20:00:00.000-04:00") },
        { "cd/ef/r05", "why", "q2", "10" }, { "cd/ef/r05", "hours", "q5", 3.0d },
        { "cd/r08", "days", "q1", 33L }, { "cd/r08", "why", "q2", "a_c" }, { "cd/r08", "paid", "q3", true },
        { "gh/r11", "why", "q2", "" }, { "gh/r11", "hours", "q5", -1.0d },
    };

    /** Where a review is below its request, who wrote it, and how it is tagged. */
    private static final Object[][] REVIEWS = {
        { "ab/r01", "review", "admin", tags("approved") }, { "ab/r02", "review", "admin", tags("pending") },
        { "ab/r02", "second", "priya", tags("rejected") }, { "cd/ef/r05", "review", "admin", tags() },
        { "cd/r08", "review", "priya", tags("approved") }, { "gh/r12", "review", "admin", tags("pending", "urgent") },
    };

    private static final String[] PROPERTIES = { "title", RANK, "ratio", "due", "flag", TAGS, "mixed" };

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @TempDir
    private Path directory;

    private Session session;

    private EntityIndexer index;

    private final List<String> differences = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception
    {
        this.session = Objects.requireNonNull(this.context.resourceResolver().adaptTo(Session.class));
        NodeTypeDefinitionScanner.get().register(this.session, List.of("SLING-INF/nodetypes/equivalence.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        for (final Object[] request : REQUESTS) {
            final Node node = node(HOMEPAGE + "/" + request[0], REQUEST);
            for (int i = 0; i < PROPERTIES.length; ++i) {
                set(node, PROPERTIES[i], request[i + 1]);
            }
        }
        for (final Object[] answer : ANSWERS) {
            final Node node = node(HOMEPAGE + "/" + answer[0] + "/" + answer[1], ANSWER);
            set(node, QUESTION, answer[2]);
            set(node, VALUE, answer[3]);
        }
        for (final Object[] review : REVIEWS) {
            final Node node = node(HOMEPAGE + "/" + review[0] + "/" + review[1], REVIEW);
            set(node, "reviewer", review[2]);
            set(node, TAGS, review[3]);
        }
        this.session.save();
        // The first start indexes everything and seals the index as it stops, so the second finds it complete
        this.index = started();
        this.index.deactivate();
        this.index = started();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        this.index.deactivate();
    }

    @Test
    void answersEveryConditionOnARequestAsTheRepositoryDoes() throws Exception
    {
        for (final String property : List.of("title", RANK, "ratio", "due", "flag", TAGS, "mixed", "absent")) {
            for (final String comparator : COMPARATORS) {
                for (final String value : VALUES) {
                    compare(listing(List.of(new Filter(property, comparator, value)), List.of()));
                }
            }
        }

        assertEquals(List.of(), this.differences);
    }

    @Test
    void answersEveryConditionOnAnAnswerAsTheRepositoryDoes() throws Exception
    {
        for (final String question : List.of("q1", "q2", "q3", "q4", "q5")) {
            for (final String comparator : COMPARATORS) {
                for (final String value : VALUES) {
                    compare(listing(List.of(), List.of(answers(question, comparator, value))));
                }
            }
        }

        assertEquals(List.of(), this.differences);
    }

    @Test
    void answersGroupsAndSeveralFamiliesAsTheRepositoryDoes() throws Exception
    {
        compare(listing(List.of(new Filter(RANK, "<", "10", "g"), new Filter("title", "=", "abc", "g"),
            new Filter("flag", "=", "true")), List.of()));
        compare(listing(List.of(new Filter(TAGS, "=", "approved", "t"), new Filter(TAGS, "=", "draft", "t")),
            List.of(answers("q1", ">", "2"))));
        compare(listing(List.of(), List.of(answers("q1", ">", "2"), answers("q2", "LIKE", "A%"))));
        compare(listing(List.of(), List.of(answers("q1", "=", "3"), answers("q1", "=", "9"))));
        // The review queue: reviews by someone that are neither approved nor rejected
        compare(listing(List.of(), List.of(new DescendantFilters(REVIEW, List.of(new Filter("reviewer", "=", "admin"),
            new Filter(TAGS, "<>", "approved"), new Filter(TAGS, "<>", "rejected"))))));

        assertEquals(List.of(), this.differences);
    }

    @Test
    void ordersAsTheRepositoryDoes() throws Exception
    {
        for (final String property : List.of("title", RANK, "ratio", "due", "flag", "absent")) {
            compare(new EntityListing(HOMEPAGE, REQUEST, List.of(), List.of(), null, property, false));
            compare(new EntityListing(HOMEPAGE, REQUEST, List.of(), List.of(), null, property, true));
        }

        assertEquals(List.of(), this.differences);
    }

    /**
     * Runs a listing through the repository and the index, and records how their answers differ.
     *
     * @param listing the entities asked for
     * @throws Exception if either cannot run it
     */
    private void compare(final EntityListing listing) throws Exception
    {
        if (!this.index.canServe(listing)) {
            this.differences.add("declined " + describe(listing));
            return;
        }
        final List<Set<String>> repository = ties(fromRepository(listing), listing.sortBy());
        final List<Set<String>> indexed = ties(fromIndex(listing), listing.sortBy());
        if (!repository.equals(indexed)) {
            this.differences.add(describe(listing) + ": repository " + repository + ", index " + indexed);
        }
    }

    private List<String> fromRepository(final EntityListing listing) throws RepositoryException
    {
        // Distinct, as the paginator reads them: a join returns an entity once per matching descendant
        final Set<String> paths = new LinkedHashSet<>();
        for (final RowIterator rows = QueryBuilder.forListing(listing).build().createQuery(this.session).execute()
            .getRows(); rows.hasNext();) {
            paths.add(rows.nextRow().getPath("n"));
        }
        return new ArrayList<>(paths);
    }

    private List<String> fromIndex(final EntityListing listing) throws Exception
    {
        final List<String> paths = new ArrayList<>();
        this.index.find(listing, this.context.resourceResolver()).forEachRemaining(paths::add);
        return paths;
    }

    /**
     * Splits an ordered answer into runs of entities that tie on the property ordered by.
     *
     * @param paths the entities, in order
     * @param property the property they are ordered by
     * @return the runs, each a set, in order
     * @throws RepositoryException if an entity cannot be read
     */
    private List<Set<String>> ties(final List<String> paths, final String property) throws RepositoryException
    {
        final List<Set<String>> runs = new ArrayList<>();
        Object previous = null;
        for (final String path : paths) {
            final Object key = sortKey(this.session.getNode(path), property);
            if (runs.isEmpty() || !Objects.equals(key, previous)) {
                runs.add(new HashSet<>());
            }
            runs.get(runs.size() - 1).add(path.substring(path.lastIndexOf('/') + 1));
            previous = key;
        }
        return runs;
    }

    private static Object sortKey(final Node node, final String property) throws RepositoryException
    {
        if (!node.hasProperty(property)) {
            return "";
        }
        final Property value = node.getProperty(property);
        return value.getType() == PropertyType.DATE ? (Object) value.getDate().getTimeInMillis() : value.getString();
    }

    private EntityIndexer started() throws Exception
    {
        final EntityIndexer indexer = new EntityIndexer();
        final Field resolvers = EntityIndexer.class.getDeclaredField("resolverFactory");
        resolvers.setAccessible(true);
        resolvers.set(indexer, this.context.getService(ResourceResolverFactory.class));
        final EntityIndexer.Config config = Mockito.mock(EntityIndexer.Config.class);
        Mockito.when(config.resource_paths()).thenReturn(HOMEPAGE);
        Mockito.when(config.entity_type()).thenReturn(REQUEST);
        Mockito.when(config.descendant_types()).thenReturn(new String[] { ANSWER, REVIEW });
        Mockito.when(config.index_directory()).thenReturn(this.directory.toString());
        Mockito.when(config.commit_seconds()).thenReturn(30L);
        indexer.activate(config, this.context.bundleContext());
        return indexer;
    }

    private Node node(final String path, final String type) throws RepositoryException
    {
        Node node = this.session.getRootNode();
        final String[] names = path.substring(1).split("/");
        for (int i = 0; i < names.length - 1; ++i) {
            node = node.hasNode(names[i]) ? node.getNode(names[i]) : node.addNode(names[i]);
        }
        return node.addNode(names[names.length - 1], type);
    }

    private static void set(final Node node, final String property, final Object value) throws RepositoryException
    {
        if (value instanceof Long number) {
            node.setProperty(property, number);
        } else if (value instanceof Double number) {
            node.setProperty(property, number);
        } else if (value instanceof Boolean flag) {
            node.setProperty(property, flag);
        } else if (value instanceof Calendar date) {
            node.setProperty(property, date);
        } else if (value instanceof String[] values) {
            node.setProperty(property, values);
        } else if (value instanceof String text) {
            node.setProperty(property, text);
        }
    }

    private static Calendar date(final String iso)
    {
        return GregorianCalendar.from(OffsetDateTime.parse(iso).toZonedDateTime());
    }

    private static String[] tags(final String... tags)
    {
        return tags;
    }

    private static DescendantFilters answers(final String question, final String comparator, final String value)
    {
        return new DescendantFilters(ANSWER, List.of(new Filter(QUESTION, "=", question),
            new Filter(VALUE, comparator, value)));
    }

    private static EntityListing listing(final List<Filter> filters, final List<DescendantFilters> descendants)
    {
        return new EntityListing(HOMEPAGE, REQUEST, filters, descendants, null, RANK, false);
    }

    private static String describe(final EntityListing listing)
    {
        return Arrays.asList(describe(listing.filters()), listing.descendants().stream()
            .map(family -> family.type() + describe(family.filters())).collect(Collectors.toList()),
            "by " + listing.sortBy() + (listing.descending() ? " desc" : "")).toString();
    }

    private static String describe(final List<Filter> filters)
    {
        return filters.stream()
            .map(filter -> filter.getName() + " " + filter.getComparator().getSymbol() + " '" + filter.getValue() + "'")
            .collect(Collectors.joining(" and ", "(", ")"));
    }
}
