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
package io.uhndata.iap.links.internal;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.links.api.LinkManager;
import io.uhndata.iap.links.models.ExternalLink;
import io.uhndata.iap.links.models.InternalLink;
import io.uhndata.iap.links.models.LinkDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Unit tests for {@link AutocreateBacklinksListener}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AutocreateBacklinksListenerTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String UUID_PROPERTY = "jcr:uuid";

    private static final String CONTAINER = LinkManager.CONTAINER_NAME;

    private static final String REFERENCES_ID = "11111111-1111-1111-1111-111111111111";

    private static final String REFERENCED_BY_ID = "22222222-2222-2222-2222-222222222222";

    private static final String THING_A_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private static final String THING_B_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private static final String LINK_PATH = "/Things/a/" + CONTAINER + "/l1";

    private final SlingContext context = new SlingContext();

    private AutocreateBacklinksListener listener;

    private LinkManagerImpl manager;

    @BeforeEach
    void setUp()
        throws ReflectiveOperationException, RepositoryException
    {
        this.context.addModelsForClasses(Content.class, LinkDefinition.class, InternalLink.class,
            ExternalLink.class);
        this.manager = new LinkManagerImpl();
        this.inject(this.manager, LinkManagerImpl.class, "resolverFactory",
            this.context.getService(ResourceResolverFactory.class));
        this.listener = new AutocreateBacklinksListener();
        this.inject(this.listener, AutocreateBacklinksListener.class, "resolverFactory",
            this.context.getService(ResourceResolverFactory.class));
        this.inject(this.listener, AutocreateBacklinksListener.class, "linkOperations", this.manager);

        final Session session = Mockito.mock(Session.class);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
        this.mockNode(session, REFERENCES_ID, "/LinkTypes/references");
        this.mockNode(session, REFERENCED_BY_ID, "/LinkTypes/referencedBy");
        this.mockNode(session, THING_A_ID, "/Things/a");
        this.mockNode(session, THING_B_ID, "/Things/b");
        Mockito.when(session.hasPermission(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
    }

    private void inject(final Object target, final Class<?> type, final String name, final Object value)
        throws ReflectiveOperationException
    {
        final Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Builds the fixture through a committing resolver: service resolvers only see committed content, exactly like
     * at runtime, where the listener fires after the user's session saved the link.
     */
    private void createCommittedFixture()
        throws Exception
    {
        final ResourceResolver committer = this.context.getService(ResourceResolverFactory.class)
            .getResourceResolver(null);
        final Resource root = committer.getResource("/");
        final Resource linkTypes = committer.create(root, "LinkTypes", Map.of());
        committer.create(linkTypes, "references", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            UUID_PROPERTY, REFERENCES_ID,
            "backlink", "/LinkTypes/referencedBy"));
        committer.create(linkTypes, "referencedBy", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            UUID_PROPERTY, REFERENCED_BY_ID,
            "backlink", "/LinkTypes/references",
            "backlinkOnly", true));
        final Resource things = committer.create(root, "Things", Map.of());
        final Resource thingA = committer.create(things, "a", Map.of(UUID_PROPERTY, THING_A_ID));
        final Resource thingB = committer.create(things, "b", Map.of(UUID_PROPERTY, THING_B_ID));
        final Resource containerA = committer.create(thingA, CONTAINER, Map.of("jcr:primaryType", "iap:Links"));
        committer.create(thingB, CONTAINER, Map.of("jcr:primaryType", "iap:Links"));
        committer.create(containerA, "l1", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", REFERENCES_ID,
            "reference", THING_B_ID));
        committer.commit();
    }

    private int childrenCount(final String path)
        throws Exception
    {
        final ResourceResolver reader = this.context.getService(ResourceResolverFactory.class)
            .getResourceResolver(null);
        final Resource parent = reader.getResource(path);
        final List<Resource> children =
            StreamSupport.stream(parent.getChildren().spliterator(), false).collect(Collectors.toList());
        return children.size();
    }

    @Test
    void completesTheBacklinkForCommittedLinks()
        throws Exception
    {
        this.createCommittedFixture();

        this.listener.onChange(List.of(new ResourceChange(ResourceChange.ChangeType.ADDED, LINK_PATH, false)));

        assertEquals(1, this.childrenCount("/Things/b/" + CONTAINER));
        assertEquals(1, this.childrenCount("/Things/a/" + CONTAINER));
    }

    @Test
    void doesNotLoopOnItsOwnBacklinks()
        throws Exception
    {
        this.createCommittedFixture();
        this.listener.onChange(List.of(new ResourceChange(ResourceChange.ChangeType.ADDED, LINK_PATH, false)));

        // The backlink's own creation event finds the pair complete and creates nothing new
        this.listener.onChange(StreamSupport
            .stream(this.context.getService(ResourceResolverFactory.class).getResourceResolver(null)
                .getResource("/Things/b/" + CONTAINER).getChildren().spliterator(), false)
            .map(child -> new ResourceChange(ResourceChange.ChangeType.ADDED, child.getPath(), false))
            .collect(Collectors.toList()));

        assertEquals(1, this.childrenCount("/Things/b/" + CONTAINER));
        assertEquals(1, this.childrenCount("/Things/a/" + CONTAINER));
    }

    @Test
    void ignoresUnrelatedChanges()
        throws Exception
    {
        this.createCommittedFixture();

        this.listener.onChange(List.of(
            new ResourceChange(ResourceChange.ChangeType.ADDED, "/Things/a", false),
            new ResourceChange(ResourceChange.ChangeType.ADDED, "/Things/a/" + CONTAINER, false),
            new ResourceChange(ResourceChange.ChangeType.ADDED, "/Things/vanished/" + CONTAINER + "/gone",
                false)));

        assertEquals(0, this.childrenCount("/Things/b/" + CONTAINER));
    }

    @Test
    void toleratesAMissingServiceUser()
        throws Exception
    {
        this.createCommittedFixture();
        final ResourceResolverFactory badFactory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(badFactory.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no such service user"));
        this.inject(this.listener, AutocreateBacklinksListener.class, "resolverFactory", badFactory);

        this.listener.onChange(List.of(new ResourceChange(ResourceChange.ChangeType.ADDED, LINK_PATH, false)));

        assertEquals(0, this.childrenCount("/Things/b/" + CONTAINER));
    }

    @Test
    void toleratesCommitFailures()
        throws Exception
    {
        this.createCommittedFixture();
        // A service resolver that reads normally but refuses to commit
        final ResourceResolver failing = Mockito.spy(this.context.getService(ResourceResolverFactory.class)
            .getServiceResourceResolver(null));
        Mockito.doThrow(new org.apache.sling.api.resource.PersistenceException("read only"))
            .when(failing).commit();
        final ResourceResolverFactory failingFactory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(failingFactory.getServiceResourceResolver(Mockito.anyMap())).thenReturn(failing);
        this.inject(this.listener, AutocreateBacklinksListener.class, "resolverFactory", failingFactory);

        this.listener.onChange(List.of(new ResourceChange(ResourceChange.ChangeType.ADDED, LINK_PATH, false)));

        assertEquals(0, this.childrenCount("/Things/b/" + CONTAINER));
    }

    @Test
    void recordsTheMissingServiceUserRatherThanOnlyLoggingIt()
        throws Exception
    {
        // Nothing downstream can tell a backlink that was never wanted from one this failed to create, so an
        // instance whose links service user is misconfigured would look healthy while quietly losing every backlink
        this.createCommittedFixture();
        final ResourceResolverFactory badFactory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(badFactory.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no such service user"));
        this.inject(this.listener, AutocreateBacklinksListener.class, "resolverFactory", badFactory);
        final ErrorLoggerService recorder = this.recordInto();

        try {
            this.listener.onChange(List.of(new ResourceChange(ResourceChange.ChangeType.ADDED, LINK_PATH, false)));

            final ArgumentCaptor<Throwable> fault = ArgumentCaptor.forClass(Throwable.class);
            Mockito.verify(recorder).logError(fault.capture(), Mockito.any(ErrorContext.class));
            assertInstanceOf(LoginException.class, fault.getValue());
        } finally {
            ErrorLogger.unsetService(recorder);
        }
    }

    @Test
    void recordsABacklinkItCouldNotCommit()
        throws Exception
    {
        this.createCommittedFixture();
        final ResourceResolver failing = Mockito.spy(this.context.getService(ResourceResolverFactory.class)
            .getServiceResourceResolver(null));
        Mockito.doThrow(new org.apache.sling.api.resource.PersistenceException("read only"))
            .when(failing).commit();
        final ResourceResolverFactory failingFactory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(failingFactory.getServiceResourceResolver(Mockito.anyMap())).thenReturn(failing);
        this.inject(this.listener, AutocreateBacklinksListener.class, "resolverFactory", failingFactory);
        final ErrorLoggerService recorder = this.recordInto();

        try {
            this.listener.onChange(List.of(new ResourceChange(ResourceChange.ChangeType.ADDED, LINK_PATH, false)));

            final ArgumentCaptor<Throwable> fault = ArgumentCaptor.forClass(Throwable.class);
            Mockito.verify(recorder).logError(fault.capture(), Mockito.any(ErrorContext.class));
            assertInstanceOf(org.apache.sling.api.resource.PersistenceException.class, fault.getValue());
        } finally {
            ErrorLogger.unsetService(recorder);
        }
    }

    /**
     * Publishes a recorder to the static facade, so that a test can see what was recorded.
     *
     * <p>
     * The facade is process-global, so every test doing this withdraws it again in a {@code finally}: the surefire
     * configuration asks for parallel classes and methods, which the JUnit 5 provider currently ignores, and a
     * leaked recorder would turn that into flakiness the day it stops ignoring it.
     * </p>
     *
     * @return the recorder, to verify against
     */
    private ErrorLoggerService recordInto()
    {
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);
        return recorder;
    }

    private void mockNode(final Session session, final String identifier, final String path)
        throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPath()).thenReturn(path);
        Mockito.when(session.getNodeByIdentifier(identifier)).thenReturn(node);
    }
}
