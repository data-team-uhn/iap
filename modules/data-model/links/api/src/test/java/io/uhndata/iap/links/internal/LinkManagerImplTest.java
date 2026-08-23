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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.links.api.LinkManager;
import io.uhndata.iap.links.models.ExternalLink;
import io.uhndata.iap.links.models.InternalLink;
import io.uhndata.iap.links.models.Link;
import io.uhndata.iap.links.models.LinkDefinition;
import io.uhndata.iap.links.models.Linkable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LinkManagerImpl}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LinkManagerImplTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String UUID_PROPERTY = "jcr:uuid";

    private static final String CONTAINER = LinkManager.CONTAINER_NAME;

    private static final String REFERENCES_ID = "11111111-1111-1111-1111-111111111111";

    private static final String REFERENCED_BY_ID = "22222222-2222-2222-2222-222222222222";

    private static final String SIMPLE_ID = "33333333-3333-3333-3333-333333333333";

    private static final String EXTERNAL_ID = "44444444-4444-4444-4444-444444444444";

    private static final String THING_A_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private static final String THING_B_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private static final String THING_B_PATH = "/Things/b";

    private static final String SIMPLE = "simple";

    private final SlingContext context = new SlingContext();

    private LinkManagerImpl manager;

    @BeforeEach
    void setUp()
        throws ReflectiveOperationException
    {
        this.context.addModelsForClasses(Content.class, LinkDefinition.class, InternalLink.class,
            ExternalLink.class, Linkable.class);
        // The bundle plugin only generates the DS metadata at packaging time, so the service is
        // instantiated directly and its references are injected by hand.
        this.manager = new LinkManagerImpl();
        this.injectFactory(new TestResolverFactory(this.context.resourceResolver(),
            this.context.getService(ResourceResolverFactory.class)));
        // The models' write behavior delegates to the manager through its internal LinkOperations face
        this.context.registerService(LinkOperations.class, this.manager);
    }

    private void injectFactory(final ResourceResolverFactory factory)
        throws ReflectiveOperationException
    {
        final Field field = LinkManagerImpl.class.getDeclaredField("resolverFactory");
        field.setAccessible(true);
        field.set(this.manager, factory);
    }

    private void createDefinitions()
    {
        this.context.create().resource("/LinkTypes/references", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            UUID_PROPERTY, REFERENCES_ID,
            "backlink", "/LinkTypes/referencedBy"));
        this.context.create().resource("/LinkTypes/referencedBy", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            UUID_PROPERTY, REFERENCED_BY_ID,
            "backlink", "/LinkTypes/references",
            "backlinkOnly", true));
        this.context.create().resource("/LinkTypes/simple", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            UUID_PROPERTY, SIMPLE_ID));
        this.context.create().resource("/LinkTypes/weak", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            UUID_PROPERTY, "55555555-5555-5555-5555-555555555555",
            "weak", true));
        this.context.create().resource("/LinkTypes/ehrChart", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            UUID_PROPERTY, EXTERNAL_ID,
            "external", true,
            "valuePattern", "[0-9]+"));
    }

    /** Things with pre-created link containers, so that no service session is involved. */
    private Resource createThings()
    {
        final Resource thing = this.context.create().resource("/Things/a", Map.of(UUID_PROPERTY, THING_A_ID));
        this.context.create().resource("/Things/a/" + CONTAINER, PRIMARY_TYPE, "iap:Links");
        this.context.create().resource(THING_B_PATH, Map.of(UUID_PROPERTY, THING_B_ID));
        this.context.create().resource(THING_B_PATH + "/" + CONTAINER, PRIMARY_TYPE, "iap:Links");
        return thing;
    }

    private Session mockSession()
        throws RepositoryException
    {
        final Session session = Mockito.mock(Session.class);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
        this.mockNode(session, REFERENCES_ID, "/LinkTypes/references");
        this.mockNode(session, REFERENCED_BY_ID, "/LinkTypes/referencedBy");
        this.mockNode(session, SIMPLE_ID, "/LinkTypes/simple");
        this.mockNode(session, THING_A_ID, "/Things/a");
        this.mockNode(session, THING_B_ID, THING_B_PATH);
        Mockito.when(session.hasPermission(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        // Unknown identifiers throw, like on a real repository, instead of returning null
        Mockito.when(session.getNodeByIdentifier("99999999-9999-9999-9999-999999999999"))
            .thenThrow(new ItemNotFoundException());
        return session;
    }

    private void mockNode(final Session session, final String identifier, final String path)
        throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPath()).thenReturn(path);
        Mockito.when(session.getNodeByIdentifier(identifier)).thenReturn(node);
    }

    @Test
    void resolvesDefinitionsByNameOrPath()
    {
        this.createDefinitions();

        assertEquals("/LinkTypes/simple", this.manager.getDefinition(SIMPLE).getPath());
        assertEquals("/LinkTypes/simple", this.manager.getDefinition("/LinkTypes/simple").getPath());
        assertNull(this.manager.getDefinition("missing"));
        assertNull(this.manager.getDefinition(null));
        // Paths outside /LinkTypes never resolve: the lookup runs with the manager's own service
        // user, so it must not be usable as an arbitrary repository read
        assertNull(this.manager.getDefinition("/Things/a"));
        assertNull(this.manager.getDefinition("/LinkTypes"));
    }

    @Test
    void rereadsTheDefinitionsWhenTheyChange()
    {
        this.createDefinitions();

        assertNull(this.manager.getDefinition("late"));
        this.context.create().resource("/LinkTypes/late", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            UUID_PROPERTY, "88888888-8888-8888-8888-888888888888"));
        // The definitions are cached, so the addition is only picked up when the listener fires
        assertNull(this.manager.getDefinition("late"));
        this.manager.onChange(List.of());
        assertNotNull(this.manager.getDefinition("late"));
    }

    @Test
    void releasesItsResolverWhenStopped()
    {
        // Stopping before anything was read has nothing to release
        this.manager.deactivate();

        this.createDefinitions();
        assertNotNull(this.manager.getDefinition(SIMPLE));
        this.manager.deactivate();
        // And the manager remains usable, lazily re-reading on the next call
        assertNotNull(this.manager.getDefinition(SIMPLE));
    }

    @Test
    void survivesAMissingDefinitionsHomepage()
    {
        assertNull(this.manager.getDefinition(SIMPLE));
    }

    @Test
    void treatsDefinitionsAsUnknownWithoutTheirServiceUser()
        throws ReflectiveOperationException
    {
        this.createDefinitions();
        this.injectFactory(new TestResolverFactory(null,
            this.context.getService(ResourceResolverFactory.class)));
        final Resource thing = this.createThings();
        final Resource destination = this.context.resourceResolver().getResource(THING_B_PATH);

        assertNull(this.manager.getDefinition(SIMPLE));
        // The failure is cached as an empty vocabulary instead of retrying the login on every call
        assertNull(this.manager.getDefinition(SIMPLE));
        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addLink(thing, this.asContent(destination), SIMPLE, null));
    }

    @Test
    void listsAllLinks()
    {
        this.createDefinitions();
        final Resource thing = this.createThings();
        assertTrue(this.linkable(this.context.create().resource("/Things/bare")).getLinks().isEmpty());
        this.context.create().resource("/Things/a/" + CONTAINER + "/l1", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE, "type", SIMPLE_ID, "reference", THING_B_ID));
        this.context.create().resource("/Things/a/" + CONTAINER + "/e1", Map.of(
            SLING_RESOURCE_TYPE, ExternalLink.RESOURCE_TYPE, "type", EXTERNAL_ID, "value", "42"));
        // An unrecognized child is skipped instead of breaking the listing
        this.context.create().resource("/Things/a/" + CONTAINER + "/junk");

        final List<Link> links = this.linkable(thing).getLinks();

        assertEquals(2, links.size());
    }

    @Test
    void listsLinksOfAType()
        throws RepositoryException
    {
        this.createDefinitions();
        this.mockSession();
        final Resource thing = this.createThings();
        this.context.create().resource("/Things/a/" + CONTAINER + "/l1", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE, "type", SIMPLE_ID, "reference", THING_B_ID));
        this.context.create().resource("/Things/a/" + CONTAINER + "/l2", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE, "type", REFERENCES_ID, "reference", THING_B_ID));

        assertEquals(1, this.linkable(thing).getLinks(SIMPLE).size());
        assertTrue(this.linkable(thing).getLinks("missing").isEmpty());
    }

    @Test
    void addsLinksInMemory()
    {
        this.createDefinitions();
        final Resource thing = this.createThings();

        final InternalLink link = this.linkable(thing)
            .addLink(this.asContent(this.context.resourceResolver().getResource(THING_B_PATH)), SIMPLE, "see also");

        assertNotNull(link);
        assertEquals(SIMPLE_ID, link.get("type"));
        assertEquals(THING_B_ID, link.get("reference"));
        assertEquals("see also", link.get("label"));
        assertEquals("iap:Link", link.get(PRIMARY_TYPE));
        // The write is not committed; that is the caller's decision
        assertTrue(this.context.resourceResolver().hasChanges());
    }

    @Test
    void deduplicatesIdenticalLinks()
    {
        this.createDefinitions();
        final Resource thing = this.createThings();
        final Resource destination = this.context.resourceResolver().getResource(THING_B_PATH);

        final InternalLink first = this.manager.addLink(thing, this.asContent(destination), SIMPLE, null);
        final InternalLink second = this.manager.addLink(thing, this.asContent(destination), SIMPLE, null);
        this.manager.addLink(thing, this.asContent(destination), SIMPLE, "other label");

        assertEquals(first.getPath(), second.getPath());
        assertEquals(2, this.linkable(thing).getLinks().size());
    }

    @Test
    void createsWeakLinksWhenTheDefinitionSaysSo()
    {
        this.createDefinitions();
        final Resource thing = this.createThings();

        final InternalLink link = this.manager.addLink(thing,
            this.asContent(this.context.resourceResolver().getResource(THING_B_PATH)), "weak", null);

        assertEquals("iap:WeakLink", link.get(PRIMARY_TYPE));
    }

    @Test
    void rejectsInvalidLinkRequests()
    {
        this.createDefinitions();
        final Resource thing = this.createThings();
        final Resource destination = this.context.resourceResolver().getResource(THING_B_PATH);
        final Resource unreferenceable = this.context.create().resource("/Things/plain");

        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addLink(thing, this.asContent(destination), "missing", null));
        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addLink(thing, this.asContent(destination), "ehrChart", null));
        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addLink(thing, this.asContent(destination), "referencedBy", null));
        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addLink(thing, this.asContent(unreferenceable), SIMPLE, null));
    }

    @Test
    void checksTypeRequirementsFromStoredTypes()
    {
        this.createDefinitions();
        this.context.create().resource("/LinkTypes/typed", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            UUID_PROPERTY, "66666666-6666-6666-6666-666666666666",
            "requiredSourceTypes", new String[]{ "iap:Entity" },
            "requiredDestinationTypes", new String[]{ "mix:referenceable" }));
        this.createThings();
        final Resource source = this.context.create().resource("/Things/typed", Map.of(
            UUID_PROPERTY, "cccccccc-cccc-cccc-cccc-cccccccccccc",
            PRIMARY_TYPE, "iap:Entity"));
        this.context.create().resource("/Things/typed/" + CONTAINER, PRIMARY_TYPE, "iap:Links");
        final Resource mixinDestination = this.context.create().resource("/Things/mixed", Map.of(
            UUID_PROPERTY, "dddddddd-dddd-dddd-dddd-dddddddddddd",
            PRIMARY_TYPE, "nt:unstructured",
            "jcr:mixinTypes", new String[]{ "mix:referenceable" }));

        // Primary type match on the source, mixin match on the destination
        assertNotNull(this.manager.addLink(source, this.asContent(mixinDestination), "typed", null));
        // An untyped source fails the requirement
        final Resource untyped = this.context.resourceResolver().getResource("/Things/a");
        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addLink(untyped, this.asContent(mixinDestination), "typed", null));
        // And so does an untyped destination
        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addLink(source, this.asContent(untyped), "typed", null));
    }

    @Test
    void checksTypeRequirementsThroughTheSession()
        throws RepositoryException
    {
        this.createDefinitions();
        final Session session = this.mockSession();
        this.context.create().resource("/LinkTypes/typed", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            UUID_PROPERTY, "66666666-6666-6666-6666-666666666666",
            "requiredSourceTypes", new String[]{ "iap:Entity" }));
        final Resource thing = this.createThings();
        final Resource destination = this.context.resourceResolver().getResource(THING_B_PATH);
        final Node sourceNode = Mockito.mock(Node.class);
        Mockito.when(session.getNode("/Things/a")).thenReturn(sourceNode);
        Mockito.when(sourceNode.isNodeType("iap:Entity")).thenReturn(true, false);

        // First call: the node type matches; second call: it doesn't
        assertNotNull(this.manager.addLink(thing, this.asContent(destination), "typed", null));
        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addLink(thing, this.asContent(destination), "typed", "second"));

        // A session that cannot even check the type fails closed
        Mockito.when(session.getNode("/Things/a")).thenThrow(new RepositoryException());
        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addLink(thing, this.asContent(destination), "typed", "third"));
    }

    @Test
    void createsTheContainerThroughTheServiceUser()
        throws Exception
    {
        this.createDefinitions();
        // Only committed resources are visible to other resolvers, service ones included
        final ResourceResolver committer = this.context.getService(ResourceResolverFactory.class)
            .getResourceResolver(null);
        committer.create(committer.getResource("/"), "Committed", Map.of(UUID_PROPERTY, THING_A_ID));
        committer.commit();
        final Resource owner = this.context.resourceResolver().getResource("/Committed");
        final Resource destination = this.context.create().resource(THING_B_PATH,
            Map.of(UUID_PROPERTY, THING_B_ID));
        this.context.create().resource(THING_B_PATH + "/" + CONTAINER, PRIMARY_TYPE, "iap:Links");

        final InternalLink link = this.manager.addLink(owner, this.asContent(destination), SIMPLE, null);

        assertNotNull(link);
        assertNotNull(owner.getChild(CONTAINER));
    }

    @Test
    void fallsBackToTheCallerSessionForUncommittedOwners()
    {
        this.createDefinitions();
        // context.create() content is not visible to service resolvers, like any uncommitted content
        final Resource owner = this.context.create().resource("/Things/fresh", Map.of(UUID_PROPERTY, THING_A_ID));
        final Resource destination = this.context.create().resource(THING_B_PATH,
            Map.of(UUID_PROPERTY, THING_B_ID));
        this.context.create().resource(THING_B_PATH + "/" + CONTAINER, PRIMARY_TYPE, "iap:Links");

        final InternalLink link = this.manager.addLink(owner, this.asContent(destination), SIMPLE, null);

        assertNotNull(link);
        assertNotNull(owner.getChild(CONTAINER));
    }

    @Test
    void checksOutVersionableOwnersWhenAddingTheContainer()
        throws Exception
    {
        this.createDefinitions();
        final Session session = this.mockSession();
        final Workspace workspace = Mockito.mock(Workspace.class);
        final VersionManager versionManager = Mockito.mock(VersionManager.class);
        Mockito.when(session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getVersionManager()).thenReturn(versionManager);
        Mockito.when(versionManager.isCheckedOut("/Committed")).thenReturn(false);
        final ResourceResolver committer = this.context.getService(ResourceResolverFactory.class)
            .getResourceResolver(null);
        committer.create(committer.getResource("/"), "Committed", Map.of(UUID_PROPERTY, THING_A_ID));
        committer.commit();
        final Resource owner = this.context.resourceResolver().getResource("/Committed");
        final Resource destination = this.context.create().resource(THING_B_PATH,
            Map.of(UUID_PROPERTY, THING_B_ID));
        this.context.create().resource(THING_B_PATH + "/" + CONTAINER, PRIMARY_TYPE, "iap:Links");

        assertNotNull(this.manager.addLink(owner, this.asContent(destination), SIMPLE, null));

        Mockito.verify(versionManager).checkout("/Committed");
        Mockito.verify(versionManager).checkin("/Committed");
    }

    @Test
    void fallsBackWhenTheServiceUserIsMissing()
        throws Exception
    {
        this.createDefinitions();
        // Definitions remain readable, only the container-creating writer user is gone
        this.injectFactory(new TestResolverFactory(this.context.resourceResolver(), null));
        final Resource owner = this.context.create().resource("/Things/fresh", Map.of(UUID_PROPERTY, THING_A_ID));
        final Resource destination = this.context.create().resource(THING_B_PATH,
            Map.of(UUID_PROPERTY, THING_B_ID));
        this.context.create().resource(THING_B_PATH + "/" + CONTAINER, PRIMARY_TYPE, "iap:Links");

        assertNotNull(this.manager.addLink(owner, this.asContent(destination), SIMPLE, null));
    }

    @Test
    void createsTheBacklinkSynchronouslyWhenPermitted()
        throws RepositoryException
    {
        this.createDefinitions();
        this.mockSession();
        final Resource thing = this.createThings();

        final InternalLink link = this.manager.addLink(thing,
            this.asContent(this.context.resourceResolver().getResource(THING_B_PATH)), "references", "pair");

        final Resource reverseContainer = this.context.resourceResolver()
            .getResource(THING_B_PATH + "/" + CONTAINER);
        final List<Resource> reverses = this.children(reverseContainer);
        assertEquals(1, reverses.size());
        assertEquals(REFERENCED_BY_ID, reverses.get(0).getValueMap().get("type", String.class));
        assertEquals(THING_A_ID, reverses.get(0).getValueMap().get("reference", String.class));
        assertEquals("pair", reverses.get(0).getValueMap().get("label", String.class));
        // And crucially, no third link ping-ponged back onto the original resource
        assertNotNull(link);
        assertEquals(1, this.children(thing.getChild(CONTAINER)).size());
    }

    @Test
    void skipsTheBacklinkWithoutPermission()
        throws RepositoryException
    {
        this.createDefinitions();
        final Session session = this.mockSession();
        Mockito.when(session.hasPermission(Mockito.anyString(), Mockito.anyString())).thenReturn(false);
        final Resource thing = this.createThings();

        assertNotNull(this.manager.addLink(thing,
            this.asContent(this.context.resourceResolver().getResource(THING_B_PATH)), "references", null));

        assertEquals(0, this.children(this.context.resourceResolver()
            .getResource(THING_B_PATH + "/" + CONTAINER)).size());
    }

    @Test
    void recognizesCompletedBacklinkPairs()
        throws RepositoryException
    {
        this.createDefinitions();
        this.mockSession();
        final Resource thing = this.createThings();
        final InternalLink link = this.manager.addLink(thing,
            this.asContent(this.context.resourceResolver().getResource(THING_B_PATH)), "references", null);

        // Asking again finds the pair complete and creates nothing new
        assertTrue(link.addBacklink());
        assertEquals(1, this.children(this.context.resourceResolver()
            .getResource(THING_B_PATH + "/" + CONTAINER)).size());
    }

    @Test
    void reportsUncreatableBacklinks()
        throws RepositoryException
    {
        this.createDefinitions();
        this.mockSession();
        this.createThings();
        // A definition with no backlink at all
        final Resource plain = this.context.create().resource("/Things/a/" + CONTAINER + "/plain", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE, "type", SIMPLE_ID, "reference", THING_B_ID));
        assertFalse(this.manager.addBacklink(plain));

        // A link whose type reference cannot be resolved at all
        final Resource untyped = this.context.create().resource("/Things/a/" + CONTAINER + "/untyped", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", "99999999-9999-9999-9999-999999999999", "reference", THING_B_ID));
        assertFalse(this.manager.addBacklink(untyped));

        // A backlink definition path that doesn't resolve
        this.context.create().resource("/LinkTypes/dangling", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            UUID_PROPERTY, "77777777-7777-7777-7777-777777777777",
            "backlink", "/LinkTypes/nowhere"));
        this.mockNode(this.context.resourceResolver().adaptTo(Session.class),
            "77777777-7777-7777-7777-777777777777", "/LinkTypes/dangling");
        final Resource dangling = this.context.create().resource("/Things/a/" + CONTAINER + "/dangling", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", "77777777-7777-7777-7777-777777777777", "reference", THING_B_ID));
        assertFalse(this.manager.addBacklink(dangling));

        // A broken destination reference
        final Resource broken = this.context.create().resource("/Things/a/" + CONTAINER + "/broken", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", REFERENCES_ID, "reference", "99999999-9999-9999-9999-999999999999"));
        assertFalse(this.manager.addBacklink(broken));

        // A resource that is not a link at all
        assertFalse(this.manager.addBacklink(this.context.create().resource("/Things/a/other")));

        // A link node outside any owning resource has nothing to point the reverse link at
        final Resource orphan = this.context.create().resource("/l1", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE, "type", REFERENCES_ID, "reference", THING_B_ID));
        assertFalse(this.manager.addBacklink(orphan));
    }

    @Test
    void refusesBacklinksToUnreferenceableSources()
        throws RepositoryException
    {
        this.createDefinitions();
        final Session session = this.mockSession();
        // The linking resource has no uuid, so the reverse link cannot reference it
        this.context.create().resource("/Things/plain");
        this.mockNode(session, "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", "/Things/plain");
        this.context.create().resource(THING_B_PATH, Map.of(UUID_PROPERTY, THING_B_ID));
        final Resource link = this.context.create().resource("/Things/plain/" + CONTAINER + "/l1", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", REFERENCES_ID, "reference", THING_B_ID));

        assertFalse(this.manager.addBacklink(link));
    }

    @Test
    void removesLinks()
        throws RepositoryException
    {
        this.createDefinitions();
        this.mockSession();
        final Resource thing = this.createThings();
        final InternalLink link = this.manager.addLink(thing,
            this.asContent(this.context.resourceResolver().getResource(THING_B_PATH)), "references", null);
        final InternalLink backlink = link.getBacklink();
        assertNotNull(backlink);

        assertTrue(link.remove(true));

        assertEquals(0, this.children(thing.getChild(CONTAINER)).size());
        assertEquals(0, this.children(this.context.resourceResolver()
            .getResource(THING_B_PATH + "/" + CONTAINER)).size());
    }

    @Test
    void removesLinksMatchingCriteria()
    {
        this.createDefinitions();
        final Resource thing = this.createThings();
        final Resource destination = this.context.resourceResolver().getResource(THING_B_PATH);
        this.manager.addLink(thing, this.asContent(destination), SIMPLE, null);
        this.manager.addLink(thing, this.asContent(destination), SIMPLE, "labeled");
        this.manager.addExternalLink(thing, "ehrChart", "42", null);

        // Unknown type and absent container are quiet no-ops
        assertEquals(0, this.linkable(thing).removeLinks(this.asContent(destination), "missing", null));
        assertEquals(0, this.linkable(this.context.create().resource("/Things/bare"))
            .removeLinks(null, SIMPLE, null));
        // The label filter distinguishes the empty label from any label
        assertEquals(1, this.linkable(thing).removeLinks(this.asContent(destination), SIMPLE, ""));
        assertEquals(1, this.linkable(thing).removeLinks(this.asContent(destination), SIMPLE, null));
        // External links can only match without a destination filter
        assertEquals(0, this.linkable(thing).removeLinks(this.asContent(destination), "ehrChart", null));
        assertEquals(1, this.linkable(thing).removeLinks(null, "ehrChart", null));
    }

    @Test
    void addsExternalLinks()
    {
        this.createDefinitions();
        final Resource thing = this.createThings();

        final ExternalLink link = this.linkable(thing).addExternalLink("ehrChart", "12345", "chart");
        final ExternalLink duplicate = this.linkable(thing).addExternalLink("ehrChart", "12345", "chart");

        assertEquals("iap:ExternalLink", link.get(PRIMARY_TYPE));
        assertEquals("12345", link.get("value"));
        assertEquals(link.getPath(), duplicate.getPath());
    }

    @Test
    void rejectsInvalidExternalLinks()
    {
        this.createDefinitions();
        final Resource thing = this.createThings();

        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addExternalLink(thing, SIMPLE, "12345", null));
        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addExternalLink(thing, "ehrChart", null, null));
        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addExternalLink(thing, "ehrChart", " ", null));
        assertThrows(IllegalArgumentException.class,
            () -> this.manager.addExternalLink(thing, "ehrChart", "not-a-number", null));
    }

    @Test
    void listsBacklinksThroughTheReferenceTracker()
        throws RepositoryException
    {
        this.createDefinitions();
        final Session session = this.mockSession();
        this.createThings();
        final Resource link = this.context.create().resource("/Things/a/" + CONTAINER + "/l1", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE, "type", SIMPLE_ID, "reference", THING_B_ID));
        final Resource target = this.context.resourceResolver().getResource(THING_B_PATH);
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(session.getNode(THING_B_PATH)).thenReturn(targetNode);
        final Node linkNode = Mockito.mock(Node.class);
        Mockito.when(linkNode.getPath()).thenReturn(link.getPath());
        final Property referenceProperty = Mockito.mock(Property.class);
        Mockito.when(referenceProperty.getParent()).thenReturn(linkNode);
        // One dangling property parent, exercising the skip
        final Node missingNode = Mockito.mock(Node.class);
        Mockito.when(missingNode.getPath()).thenReturn("/no/such/node");
        final Property danglingProperty = Mockito.mock(Property.class);
        Mockito.when(danglingProperty.getParent()).thenReturn(missingNode);
        final PropertyIterator references = this.iterator(referenceProperty, danglingProperty);
        final PropertyIterator weakReferences = this.iterator();
        Mockito.when(targetNode.getReferences("reference")).thenReturn(references);
        Mockito.when(targetNode.getWeakReferences("reference")).thenReturn(weakReferences);

        final List<InternalLink> backlinks = this.linkable(target).getBacklinks();

        assertEquals(1, backlinks.size());
        assertEquals(link.getPath(), backlinks.get(0).getPath());
    }

    @Test
    void backlinkListingNeedsARealRepository()
        throws RepositoryException
    {
        this.createDefinitions();
        final Resource thing = this.createThings();
        // Without a JCR session there is no reference tracking
        assertTrue(this.linkable(thing).getBacklinks().isEmpty());

        // And a failing session yields an empty result instead of breaking
        final Session session = this.mockSession();
        Mockito.when(session.getNode("/Things/a")).thenThrow(new RepositoryException());
        assertTrue(this.linkable(thing).getBacklinks().isEmpty());
    }

    @Test
    void recordsAVocabularyItCouldNotRead()
        throws ReflectiveOperationException
    {
        // An empty vocabulary makes every link type read as undefined, so a misconfigured service user and a link
        // type nobody declared are the same story to a caller. Only the record tells them apart
        this.createDefinitions();
        this.injectFactory(new TestResolverFactory(null,
            this.context.getService(ResourceResolverFactory.class)));
        final ErrorLoggerService recorder = this.recordInto();

        try {
            assertNull(this.manager.getDefinition(SIMPLE));

            Mockito.verify(recorder).logError(Mockito.any(LoginException.class), Mockito.any(ErrorContext.class));
        } finally {
            ErrorLogger.unsetService(recorder);
        }
    }

    @Test
    void recordsBacklinksItCouldNotList()
        throws RepositoryException
    {
        // The empty list this returns is exactly what a resource nothing points at returns, which is the one
        // failure a reader of a backlink listing has no way to notice
        this.createDefinitions();
        final Resource thing = this.createThings();
        final Session session = this.mockSession();
        Mockito.when(session.getNode("/Things/a")).thenThrow(new RepositoryException());
        final ErrorLoggerService recorder = this.recordInto();

        try {
            assertTrue(this.linkable(thing).getBacklinks().isEmpty());

            Mockito.verify(recorder).logError(Mockito.any(RepositoryException.class),
                Mockito.any(ErrorContext.class));
        } finally {
            ErrorLogger.unsetService(recorder);
        }
    }

    /**
     * Publishes a recorder to the static facade, so that a test can see what was recorded. Withdrawn in a
     * {@code finally} by every test that calls it: the facade is process-global.
     *
     * @return the recorder, to verify against
     */
    private ErrorLoggerService recordInto()
    {
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);
        return recorder;
    }

    @Test
    void deleteFailuresAreReported()
        throws Exception
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.getPath()).thenReturn("/Things/a/" + CONTAINER + "/l1");
        Mockito.doThrow(new org.apache.sling.api.resource.PersistenceException("locked"))
            .when(resolver).delete(resource);

        assertFalse(this.manager.delete(resolver, resource));
    }

    @Test
    void containerCreationFailuresSurfaceAsIllegalArguments()
        throws Exception
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        final Resource owner = Mockito.mock(Resource.class);
        Mockito.when(owner.getPath()).thenReturn("/Things/a");
        Mockito.when(resolver.create(Mockito.eq(owner), Mockito.anyString(), Mockito.anyMap()))
            .thenThrow(new org.apache.sling.api.resource.PersistenceException("read only"));

        assertThrows(IllegalArgumentException.class, () -> this.manager.createContainer(resolver, owner));
    }

    @Test
    void linkCreationFailuresSurfaceAsIllegalArguments()
        throws Exception
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        final Resource parent = Mockito.mock(Resource.class);
        Mockito.when(parent.getPath()).thenReturn("/Things/a");
        final Resource container = Mockito.mock(Resource.class);
        Mockito.when(container.getResourceResolver()).thenReturn(resolver);
        Mockito.when(container.getParent()).thenReturn(parent);
        Mockito.when(resolver.create(Mockito.eq(container), Mockito.anyString(), Mockito.anyMap()))
            .thenThrow(new org.apache.sling.api.resource.PersistenceException("read only"));

        assertThrows(IllegalArgumentException.class, () -> this.manager
            .createLinkNode(container, "iap:Link", SIMPLE_ID, "reference", THING_B_ID, null));
    }

    @Test
    void skipsTheBacklinkWhenThePermissionCheckFails()
        throws RepositoryException
    {
        this.createDefinitions();
        final Session session = this.mockSession();
        Mockito.when(session.hasPermission(Mockito.anyString(), Mockito.anyString()))
            .thenThrow(new RepositoryException());
        final Resource thing = this.createThings();

        assertNotNull(this.manager.addLink(thing,
            this.asContent(this.context.resourceResolver().getResource(THING_B_PATH)), "references", null));

        assertEquals(0, this.children(this.context.resourceResolver()
            .getResource(THING_B_PATH + "/" + CONTAINER)).size());
    }

    @Test
    void removesPlainLinksWithoutBacklinkHandling()
        throws RepositoryException
    {
        this.createDefinitions();
        this.mockSession();
        final Resource thing = this.createThings();
        final Resource destination = this.context.resourceResolver().getResource(THING_B_PATH);
        final InternalLink first = this.manager.addLink(thing, this.asContent(destination), SIMPLE, null);
        final InternalLink second = this.manager.addLink(thing, this.asContent(destination), SIMPLE, "labeled");

        // No backlink exists, so asking to remove it is a quiet no-op
        assertTrue(first.remove(true));
        // And not asking about backlinks at all
        assertTrue(second.remove(false));
        // External links never have a backlink to chase
        final ExternalLink external = this.manager.addExternalLink(thing, "ehrChart", "42", null);
        assertTrue(external.remove(true));
        assertEquals(0, this.children(thing.getChild(CONTAINER)).size());
    }

    @Test
    void removingANonLinkResourceIsRefused()
    {
        assertFalse(this.manager.remove(this.context.create().resource("/Things/junk"), true));
    }

    @Test
    void skipsTheBacklinkWhenTheResolverCannotSeeTheEndpoints()
        throws RepositoryException
    {
        this.createDefinitions();
        this.mockSession();
        this.createThings();
        final Resource link = this.context.create().resource("/Things/a/" + CONTAINER + "/l1", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE, "type", REFERENCES_ID, "reference", THING_B_ID));
        // A link node handed in through a resolver that cannot see the link's endpoints
        final ResourceResolver blind = Mockito.mock(ResourceResolver.class);
        final Resource wrapped = Mockito.mock(Resource.class, AdditionalAnswers.delegatesTo(link));
        Mockito.doReturn(blind).when(wrapped).getResourceResolver();

        // Neither endpoint is visible
        assertFalse(this.manager.addBacklink(wrapped));
        // The linked resource is visible, but the linking one is not
        Mockito.when(blind.getResource(THING_B_PATH))
            .thenReturn(this.context.resourceResolver().getResource(THING_B_PATH));
        assertFalse(this.manager.addBacklink(wrapped));
        // Both are visible, but the resolver has no JCR session to check write permissions with
        Mockito.when(blind.getResource("/Things/a"))
            .thenReturn(this.context.resourceResolver().getResource("/Things/a"));
        assertFalse(this.manager.addBacklink(wrapped));
    }

    @Test
    void skipsAnInvisibleBacklinkOnRemoval()
        throws RepositoryException
    {
        this.createDefinitions();
        this.mockSession();
        final Resource thing = this.createThings();
        final InternalLink link = this.manager.addLink(thing,
            this.asContent(this.context.resourceResolver().getResource(THING_B_PATH)), "references", null);
        assertNotNull(link.getBacklink());
        // The link is deleted through a resolver that cannot see the reverse link
        final Resource linkResource = this.context.resourceResolver().getResource(link.getPath());
        final ResourceResolver blind = Mockito.mock(ResourceResolver.class);
        final Resource wrapped = Mockito.mock(Resource.class, AdditionalAnswers.delegatesTo(linkResource));
        Mockito.doReturn(blind).when(wrapped).getResourceResolver();

        assertTrue(this.manager.remove(wrapped, true));

        // The invisible reverse was quietly skipped
        assertEquals(1, this.children(this.context.resourceResolver()
            .getResource(THING_B_PATH + "/" + CONTAINER)).size());
    }

    @Test
    void retypesReferencePropertiesThroughTheJcrApi()
        throws RepositoryException
    {
        this.createDefinitions();
        this.createThings();
        final Node node = Mockito.mock(Node.class);
        final Property typeProperty = Mockito.mock(Property.class);
        Mockito.when(typeProperty.getString()).thenReturn(SIMPLE_ID);
        final Property referenceProperty = Mockito.mock(Property.class);
        Mockito.when(referenceProperty.getString()).thenReturn(THING_B_ID);
        Mockito.when(node.getProperty("type")).thenReturn(typeProperty);
        Mockito.when(node.getProperty("reference")).thenReturn(referenceProperty);
        this.context.registerAdapter(Resource.class, Node.class, node);
        final Resource thing = this.context.resourceResolver().getResource("/Things/a");

        this.manager.addLink(thing,
            this.asContent(this.context.resourceResolver().getResource(THING_B_PATH)), "weak", null);
        this.manager.addLink(thing,
            this.asContent(this.context.resourceResolver().getResource(THING_B_PATH)), SIMPLE, null);

        Mockito.verify(node, Mockito.times(2))
            .setProperty("type", SIMPLE_ID, javax.jcr.PropertyType.REFERENCE);
        Mockito.verify(node).setProperty("reference", THING_B_ID, javax.jcr.PropertyType.WEAKREFERENCE);
        Mockito.verify(node).setProperty("reference", THING_B_ID, javax.jcr.PropertyType.REFERENCE);
    }

    @Test
    void retypingFailuresSurfaceAsIllegalArguments()
        throws RepositoryException
    {
        this.createDefinitions();
        this.createThings();
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getProperty(Mockito.anyString())).thenThrow(new RepositoryException());
        this.context.registerAdapter(Resource.class, Node.class, node);
        final Resource thing = this.context.resourceResolver().getResource("/Things/a");

        assertThrows(IllegalArgumentException.class, () -> this.manager.addLink(thing,
            this.asContent(this.context.resourceResolver().getResource(THING_B_PATH)), SIMPLE, null));
    }

    private PropertyIterator iterator(final Property... properties)
    {
        final PropertyIterator iterator = Mockito.mock(PropertyIterator.class);
        final Boolean[] more = new Boolean[properties.length];
        for (int i = 0; i < properties.length; ++i) {
            more[i] = i < properties.length - 1 ? Boolean.TRUE : Boolean.FALSE;
        }
        if (properties.length == 0) {
            Mockito.when(iterator.hasNext()).thenReturn(false);
        } else {
            Mockito.when(iterator.hasNext()).thenReturn(true, more);
            Mockito.when(iterator.nextProperty()).thenReturn(properties[0],
                Arrays.copyOfRange(properties, 1, properties.length));
        }
        return iterator;
    }

    private List<Resource> children(final Resource parent)
    {
        return StreamSupport.stream(parent.getChildren().spliterator(), false).collect(Collectors.toList());
    }

    private Linkable linkable(final Resource resource)
    {
        return resource.adaptTo(Linkable.class);
    }

    private Content asContent(final Resource resource)
    {
        return resource.adaptTo(Content.class);
    }
}
