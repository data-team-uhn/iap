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
package io.uhndata.iap.demos.timeoff.internal;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.models.Taggable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NightlyUrgencySweep}: which requests it visits, and that one it cannot write does not cost
 * the rest their answer. What counts as urgent is {@link TimeOffUrgency}'s and tested there.
 *
 * <p>The query is stubbed rather than run: the mock repository has no index behind
 * {@code findResources}, and what this class owns is what it does with the results rather than the statement that
 * produced them.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class NightlyUrgencySweepTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String OWN_VERSION = "/Schemas/timeOffRequest/v1";

    private static final String OTHER_VERSION = "/Schemas/somethingElse/v1";

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final NightlyUrgencySweep sweep = new NightlyUrgencySweep();

    @BeforeEach
    void setUp()
    {
        taggable();
        this.context.create().resource(NightlyUrgencySweep.OWN_SCHEMA, Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request", "active", true));
        for (final String version : List.of(OWN_VERSION, OTHER_VERSION)) {
            this.context.create().resource(version, Map.of(
                TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
            this.context.create().resource(version + "/details/startDate", Map.of(
                TYPE, Question.RESOURCE_TYPE, "text", "When?", "dataType", "date"));
        }
    }

    @Test
    void flagsTheRequestsOfItsOwnSchemaThatAreAboutToStart()
    {
        request("mine", OWN_VERSION, "2026-09-16");

        this.sweep.sweep(resolverFinding("/Submissions/mine"), TODAY);

        assertTrue(tags("mine").contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void asksOnlyForTheRequestsOfItsOwnSchema()
    {
        // A sweep this demo installed has no business judging somebody else's requests, and it says so in the
        // query rather than by reading every submission and discarding most of them
        final List<String> asked = new ArrayList<>();
        this.sweep.sweep(recording(asked), TODAY);

        assertEquals(1, asked.size());
        assertTrue(asked.get(0).contains("submission.[schema] = '" + identifierOf(NightlyUrgencySweep.OWN_SCHEMA)
            + "'"));
        // Compared against the schema, not against its versions, so no join is needed to reach either
        assertFalse(asked.get(0).contains("JOIN"));
    }

    @Test
    void judgesNothingWhenItsSchemaIsNotInstalled() throws PersistenceException
    {
        // A demo bundle can be deployed without its content, and a sweep that then queried on an empty
        // identifier would match every submission in the repository
        final List<String> asked = new ArrayList<>();
        this.context.resourceResolver().delete(
            Objects.requireNonNull(this.context.resourceResolver().getResource(NightlyUrgencySweep.OWN_SCHEMA)));

        assertDoesNotThrow(() -> this.sweep.sweep(recording(asked), TODAY));
        assertTrue(asked.isEmpty());
    }

    @Test
    void judgesNothingWhenTheSchemaCannotBeIdentified()
    {
        // Without an identifier there is no way to say "this schema" in the query, and asking anyway would
        // either match nothing or match everything
        final List<String> asked = new ArrayList<>();
        final ResourceResolver unidentifiable = new ResourceResolverWrapper(recording(asked))
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource real = super.getResource(path);
                if (real == null || !NightlyUrgencySweep.OWN_SCHEMA.equals(path)) {
                    return real;
                }
                return new ResourceWrapper(real)
                {
                    @Override
                    public <T> T adaptTo(final Class<T> type)
                    {
                        return type == Node.class ? type.cast(Mockito.mock(Node.class, invocation -> {
                            throw new RepositoryException("boom");
                        })) : super.adaptTo(type);
                    }
                };
            }
        };

        assertDoesNotThrow(() -> this.sweep.sweep(unidentifiable, TODAY));
        assertTrue(asked.isEmpty());
    }

    @Test
    void carriesOnPastARequestThatHasGoneAway()
    {
        // A query result is a snapshot: something deleted between reading the list and reaching it is not an error
        request("mine", OWN_VERSION, "2026-09-16");

        this.sweep.sweep(resolverFinding("/Submissions/gone", "/Submissions/mine"), TODAY);

        assertTrue(tags("mine").contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void carriesOnPastARequestItCannotWrite() throws PersistenceException
    {
        // One unwritable request must not cost every later one its answer, which is the whole reason the marking
        // is wrapped rather than left to abort the sweep
        request("unwritable", OWN_VERSION, "2026-09-16");
        request("mine", OWN_VERSION, "2026-09-16");

        this.sweep.sweep(resolverFinding("/Submissions/unwritable", "/Submissions/mine"), TODAY);

        assertFalse(tags("unwritable").contains(TimeOffUrgency.URGENT_TAG));
        assertTrue(tags("mine").contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void schedulesItselfAndStandsDownAgain() throws ReflectiveOperationException
    {
        final Scheduler scheduler = Mockito.mock(Scheduler.class);
        Mockito.when(scheduler.EXPR(Mockito.anyString())).thenReturn(Mockito.mock(ScheduleOptions.class));
        inject("scheduler", scheduler);

        this.sweep.activate();
        this.sweep.deactivate();

        Mockito.verify(scheduler).schedule(Mockito.same(this.sweep), Mockito.any());
        Mockito.verify(scheduler).unschedule(Mockito.anyString());
    }

    @Test
    void judgesEveryRequestWhenTheClockCallsIt() throws Exception
    {
        // Today rather than a date of the test's choosing, because that is the whole point of the nightly run:
        // it asks the question again against a calendar that has moved
        request("mine", OWN_VERSION, LocalDate.now().plusDays(1).toString());
        inject("resolverFactory", factoryReturning(resolverFinding("/Submissions/mine")));

        this.sweep.run();

        assertTrue(tags("mine").contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void survivesAMissingServiceUser() throws Exception
    {
        // The sweep runs on a clock, so there is nobody to report a failure to: it says so in the log and leaves
        // every request as it found it
        request("mine", OWN_VERSION, LocalDate.now().plusDays(1).toString());
        final ResourceResolverFactory refusing = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(refusing.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no such service user"));
        inject("resolverFactory", refusing);

        assertDoesNotThrow(this.sweep::run);
        assertFalse(tags("mine").contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void reportsBeingUnableToRecordWhatItDecided() throws Exception
    {
        // The tags went on in the session and the commit is what failed, so nothing is saved — and a sweep that
        // threw here would take the scheduler's job down with it
        request("mine", OWN_VERSION, "2026-09-16");
        final ResourceResolver failing = new ResourceResolverWrapper(resolverFinding("/Submissions/mine"))
        {
            @Override
            public boolean hasChanges()
            {
                return true;
            }

            @Override
            public void commit() throws PersistenceException
            {
                throw new PersistenceException("the repository would not take it");
            }
        };

        assertDoesNotThrow(() -> this.sweep.sweep(failing, TODAY));
    }

    @Test
    void saysWhenItRuns()
    {
        // Just after midnight, when "tomorrow" has become "today" for the requests that were waiting on it
        assertEquals("0 5 0 * * ?", NightlyUrgencySweep.SCHEDULE);
    }

    /** A submission of the given schema version, answering the start date. */
    private void request(final String name, final String version, final String startDate)
    {
        final String path = "/Submissions/" + name;
        final Resource submission = this.context.create().resource(path, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", name, "tags", new String[] {"submitted"}));
        reference(submission, version, "schemaVersion");
        this.context.create().resource(path + "/start", Map.of(
            TYPE, Answer.RESOURCE_TYPE, "question", identifierOf(version + "/details/startDate"),
            "value", new String[] {startDate}));
    }

    /** A resolver that notes down every query it is asked and answers each with nothing. */
    private ResourceResolver recording(final List<String> asked)
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Iterator<Resource> findResources(final String query, final String language)
            {
                asked.add(query);
                return Collections.emptyIterator();
            }
        };
    }

    /** A resolver whose query answers with exactly these paths, in this order. */
    private ResourceResolver resolverFinding(final String... paths)
    {
        return finding(this.context.resourceResolver(), paths);
    }

    private static ResourceResolver finding(final ResourceResolver delegate, final String... paths)
    {
        return new ResourceResolverWrapper(delegate)
        {
            @Override
            public Iterator<Resource> findResources(final String query, final String language)
            {
                // A path whose resource has gone is still a row the query returns, so it is handed over as
                // one: filtering it out here would hide the very case the sweep guards against
                return List.of(paths).stream()
                    .map(path -> {
                        final Resource found = delegate.getResource(path);
                        if (found != null) {
                            return found;
                        }
                        final Resource stale = Mockito.mock(Resource.class);
                        Mockito.when(stale.getPath()).thenReturn(path);
                        return stale;
                    })
                    .iterator();
            }
        };
    }

    /** A factory handing out this resolver, and not minding it being closed. */
    private static ResourceResolverFactory factoryReturning(final ResourceResolver resolver) throws LoginException
    {
        // Closing the wrapper would close the context's own resolver, which every later assertion reads through
        final ResourceResolver unclosable = new ResourceResolverWrapper(resolver)
        {
            @Override
            public void close()
            {
                // Deliberately nothing
            }
        };
        final ResourceResolverFactory factory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(factory.getServiceResourceResolver(Mockito.anyMap())).thenReturn(unclosable);
        return factory;
    }

    private void inject(final String name, final Object value) throws ReflectiveOperationException
    {
        final Field field = NightlyUrgencySweep.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.sweep, value);
    }

    private Set<String> tags(final String name)
    {
        final Resource resource = this.context.resourceResolver().getResource("/Submissions/" + name);
        assertNotNull(resource);
        return Set.of(resource.getValueMap().get("tags", new String[0]));
    }

    private String identifierOf(final String path)
    {
        try {
            final Resource resource = this.context.resourceResolver().getResource(path);
            assertNotNull(resource);
            final Node node = resource.adaptTo(Node.class);
            assertNotNull(node);
            return node.getIdentifier();
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private void reference(final Resource from, final String toPath, final String property)
    {
        try {
            final Node source = from.adaptTo(Node.class);
            final Resource target = this.context.resourceResolver().getResource(toPath);
            assertNotNull(source);
            assertNotNull(target);
            source.setProperty(property, target.adaptTo(Node.class));
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private void taggable()
    {
        this.context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
            final Taggable taggable = Mockito.mock(Taggable.class);
            Mockito.when(taggable.hasOwnTag(Mockito.anyString())).thenAnswer(invocation ->
                Set.of(resource.getValueMap().get("tags", new String[0])).contains(invocation.getArgument(0)));
            try {
                Mockito.when(taggable.tag(Mockito.anyString(), Mockito.anyBoolean()))
                    .thenAnswer(invocation -> write(resource, invocation.getArgument(0), true));
                Mockito.when(taggable.untag(Mockito.anyString(), Mockito.anyBoolean()))
                    .thenAnswer(invocation -> write(resource, invocation.getArgument(0), false));
            } catch (final PersistenceException e) {
                // Declared by the methods being stubbed, thrown by neither the stubbing nor the mock
                throw new IllegalStateException(e);
            }
            return taggable;
        });
    }

    private boolean write(final Resource resource, final String tag, final boolean placing)
        throws PersistenceException
    {
        // One request the repository will not take a tag for, which is what the sweep has to survive. Refused
        // here rather than by sabotaging the resource, because this is where a refusal actually comes from.
        if (resource.getPath().endsWith("unwritable")) {
            throw new PersistenceException("Nothing may be written to " + resource.getPath());
        }
        final ModifiableValueMap properties = resource.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            return false;
        }
        final Set<String> tags = new LinkedHashSet<>(Set.of(properties.get("tags", new String[0])));
        final boolean changed = placing ? tags.add(tag) : tags.remove(tag);
        properties.put("tags", tags.toArray(String[]::new));
        return changed;
    }
}
