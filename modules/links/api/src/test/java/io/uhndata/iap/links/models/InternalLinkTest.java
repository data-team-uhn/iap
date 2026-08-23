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
package io.uhndata.iap.links.models;

import java.util.Map;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link InternalLink} and the shared {@link Link} behavior.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class InternalLinkTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String DEFINITION_ID = "11111111-1111-1111-1111-111111111111";

    private static final String BACK_DEFINITION_ID = "22222222-2222-2222-2222-222222222222";

    private static final String THING_A_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private static final String THING_B_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private final SlingContext context = new SlingContext();

    private Session session;

    @BeforeEach
    void setUp()
        throws RepositoryException
    {
        this.context.addModelsForClasses(Content.class, LinkDefinition.class, InternalLink.class,
            ExternalLink.class);
        this.session = Mockito.mock(Session.class);
        this.context.registerAdapter(ResourceResolver.class, Session.class, this.session);
        this.mockNode(DEFINITION_ID, "/LinkTypes/references");
        this.mockNode(BACK_DEFINITION_ID, "/LinkTypes/referencedBy");
        this.mockNode(THING_A_ID, "/Things/a");
        this.mockNode(THING_B_ID, "/Things/b");
        // Unknown identifiers throw, like on a real repository, instead of returning null
        Mockito.when(this.session.getNodeByIdentifier("99999999-9999-9999-9999-999999999999"))
            .thenThrow(new ItemNotFoundException());
    }

    private void mockNode(final String identifier, final String path)
        throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPath()).thenReturn(path);
        Mockito.when(this.session.getNodeByIdentifier(identifier)).thenReturn(node);
    }

    /** The standard fixture: a cross-referencing definition pair, two things, and a link from a to b. */
    private Resource createFixture()
    {
        this.context.create().resource("/LinkTypes/references", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "jcr:uuid", DEFINITION_ID,
            "label", "References",
            "backlink", "/LinkTypes/referencedBy"));
        this.context.create().resource("/LinkTypes/referencedBy", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "jcr:uuid", BACK_DEFINITION_ID,
            "backlink", "/LinkTypes/references",
            "backlinkOnly", true));
        this.context.create().resource("/Things/a", Map.of("jcr:uuid", THING_A_ID, "title", "Thing A"));
        this.context.create().resource("/Things/b", Map.of("jcr:uuid", THING_B_ID, "title", "Thing B"));
        return this.context.create().resource("/Things/a/link:links/l1", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", DEFINITION_ID,
            "reference", THING_B_ID,
            "label", "see also"));
    }

    private Resource createBacklinkFixture()
    {
        return this.context.create().resource("/Things/b/link:links/l2", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", BACK_DEFINITION_ID,
            "reference", THING_A_ID));
    }

    @Test
    void dispatchesToTheConcreteModel()
    {
        final Resource resource = this.createFixture();

        final Link link = resource.adaptTo(Link.class);

        assertEquals(InternalLink.class, link.getClass());
        assertEquals("see also", link.getLabel());
        assertEquals("References", link.getDefinition().getLabel());
        assertEquals("/Things/a", link.getSource().getPath());
    }

    @Test
    void resolvesTheDestination()
    {
        final InternalLink link = (InternalLink) this.createFixture().adaptTo(Link.class);

        assertNotNull(link.getDestination());
        assertEquals("/Things/b", link.getDestination().getPath());
        assertFalse(link.isWeak());
    }

    @Test
    void weakLinksAreRecognizedThroughTheirResourceType()
    {
        this.createFixture();
        final Resource resource = this.context.create().resource("/Things/a/link:links/w1", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.WEAK_RESOURCE_TYPE,
            "sling:resourceSuperType", InternalLink.RESOURCE_TYPE,
            "type", DEFINITION_ID,
            "reference", THING_B_ID));

        final Link link = resource.adaptTo(Link.class);

        assertEquals(InternalLink.class, link.getClass());
        assertTrue(((InternalLink) link).isWeak());
    }

    @Test
    void findsTheCompletedBacklink()
    {
        final InternalLink link = (InternalLink) this.createFixture().adaptTo(Link.class);
        assertNull(link.getBacklink());
        assertFalse(link.isSymmetric());

        final InternalLink backlink = (InternalLink) this.createBacklinkFixture().adaptTo(Link.class);

        assertNotNull(link.getBacklink());
        assertEquals("/Things/b/link:links/l2", link.getBacklink().getPath());
        assertTrue(link.isSymmetric());
        // The pairing is symmetric: the backlink also recognizes the original as its reverse
        assertTrue(backlink.isReverseOf(link));
        assertTrue(link.isReverseOf(backlink));
    }

    @Test
    void unrelatedLinksAreNotReverses()
    {
        final InternalLink link = (InternalLink) this.createFixture().adaptTo(Link.class);
        // Same definition pair, but pointing at a third resource: endpoints don't swap
        final Resource unrelated = this.context.create().resource("/Things/b/link:links/l3", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", BACK_DEFINITION_ID,
            "reference", THING_B_ID));

        assertFalse(link.isReverseOf((InternalLink) unrelated.adaptTo(Link.class)));
        assertNull(link.getBacklink());
    }

    @Test
    void brokenLinksAreNotReverses()
    {
        final InternalLink link = (InternalLink) this.createFixture().adaptTo(Link.class);
        final Resource broken = this.context.create().resource("/Things/b/link:links/broken", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", BACK_DEFINITION_ID,
            "reference", "99999999-9999-9999-9999-999999999999"));

        assertFalse(link.isReverseOf((InternalLink) broken.adaptTo(Link.class)));
        // And symmetrically, a link with no reachable destination reverses nothing
        assertFalse(((InternalLink) broken.adaptTo(Link.class)).isReverseOf(link));
    }

    @Test
    void asymmetricDefinitionPairsCrossMatch()
        throws RepositoryException
    {
        this.createFixture();
        // Only the forward definition declares the backlink; the reverse definition is bare
        this.context.create().resource("/LinkTypes/parentOf", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "jcr:uuid", "44444444-4444-4444-4444-444444444444",
            "backlink", "/LinkTypes/childOf"));
        this.context.create().resource("/LinkTypes/childOf", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "jcr:uuid", "55555555-5555-5555-5555-555555555555"));
        this.mockNode("44444444-4444-4444-4444-444444444444", "/LinkTypes/parentOf");
        this.mockNode("55555555-5555-5555-5555-555555555555", "/LinkTypes/childOf");
        final InternalLink forward = (InternalLink) this.context.create()
            .resource("/Things/a/link:links/f1", Map.of(
                SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
                "type", "44444444-4444-4444-4444-444444444444",
                "reference", THING_B_ID))
            .adaptTo(Link.class);
        final InternalLink reverse = (InternalLink) this.context.create()
            .resource("/Things/b/link:links/r1", Map.of(
                SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
                "type", "55555555-5555-5555-5555-555555555555",
                "reference", THING_A_ID))
            .adaptTo(Link.class);

        // The bare side recognizes the pair through the OTHER side's backlink declaration
        assertTrue(reverse.isReverseOf(forward));
        assertTrue(forward.isReverseOf(reverse));
    }

    @Test
    void linksWithUnresolvableDefinitionsAreNotReverses()
    {
        final InternalLink link = (InternalLink) this.createFixture().adaptTo(Link.class);
        final Resource untyped = this.context.create().resource("/Things/b/link:links/untyped", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", "99999999-9999-9999-9999-999999999999",
            "reference", THING_A_ID));

        assertFalse(link.isReverseOf((InternalLink) untyped.adaptTo(Link.class)));
        // With no resolvable definition there is no template either; the natural label still works
        assertEquals("a", ((InternalLink) untyped.adaptTo(Link.class)).getTargetLabel());
    }

    @Test
    void danglingBacklinkDeclarationsDoNotCrossMatch()
        throws RepositoryException
    {
        this.createFixture();
        this.context.create().resource("/LinkTypes/dangling", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "jcr:uuid", "66666666-6666-6666-6666-666666666666",
            "backlink", "/LinkTypes/nowhere"));
        this.context.create().resource("/LinkTypes/bare", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "jcr:uuid", "77777777-7777-7777-7777-777777777777"));
        this.mockNode("66666666-6666-6666-6666-666666666666", "/LinkTypes/dangling");
        this.mockNode("77777777-7777-7777-7777-777777777777", "/LinkTypes/bare");
        final InternalLink bare = (InternalLink) this.context.create()
            .resource("/Things/a/link:links/bare1", Map.of(
                SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
                "type", "77777777-7777-7777-7777-777777777777",
                "reference", THING_B_ID))
            .adaptTo(Link.class);
        final InternalLink dangling = (InternalLink) this.context.create()
            .resource("/Things/b/link:links/d1", Map.of(
                SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
                "type", "66666666-6666-6666-6666-666666666666",
                "reference", THING_A_ID))
            .adaptTo(Link.class);

        assertFalse(bare.isReverseOf(dangling));
        // The dangling declaration fails the cross-match from its own side too
        assertFalse(dangling.isReverseOf(bare));
        // Two backlink-less definitions never pair, even with swapped endpoints
        final InternalLink bareBack = (InternalLink) this.context.create()
            .resource("/Things/b/link:links/bareBack", Map.of(
                SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
                "type", "77777777-7777-7777-7777-777777777777",
                "reference", THING_A_ID))
            .adaptTo(Link.class);
        assertFalse(bare.isReverseOf(bareBack));
    }

    @Test
    void rootLevelNodesHaveNoSource()
        throws RepositoryException
    {
        this.createFixture();
        this.context.create().resource("/LinkTypes/sourced", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "jcr:uuid", "88888888-8888-8888-8888-888888888888",
            "targetLabelTemplate", "{sourceName}x"));
        this.mockNode("88888888-8888-8888-8888-888888888888", "/LinkTypes/sourced");
        final Resource orphan = this.context.create().resource("/orphanlink", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", "88888888-8888-8888-8888-888888888888",
            "reference", THING_B_ID));
        final InternalLink link = (InternalLink) orphan.adaptTo(Link.class);

        assertNull(link.getSource());
        assertEquals("x", link.getTargetLabel());
    }

    @Test
    void toleratesALinkNodeAtTheRepositoryRoot()
    {
        // Cannot happen with the real node types, but the owner lookup must not crash on it
        final InternalLink link = this.context.resourceResolver().getResource("/").adaptTo(InternalLink.class);

        assertNull(link.getSource());
    }

    @Test
    void templatesOnBrokenDestinationsResolveToNothing()
        throws RepositoryException
    {
        this.createFixture();
        this.context.create().resource("/LinkTypes/labeled", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "jcr:uuid", "88888888-8888-8888-8888-888888888888",
            "targetLabelTemplate", "{label}{name}{property:title}"));
        this.mockNode("88888888-8888-8888-8888-888888888888", "/LinkTypes/labeled");
        final Resource broken = this.context.create().resource("/Things/a/link:links/nowhere", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", "88888888-8888-8888-8888-888888888888",
            "reference", "99999999-9999-9999-9999-999999999999"));

        // No link label and no reachable destination leave every placeholder empty
        assertEquals("", broken.adaptTo(Link.class).getTargetLabel());
    }

    @Test
    void blankTemplatesFallBackToTheNaturalLabel()
        throws RepositoryException
    {
        this.createFixture();
        this.context.create().resource("/LinkTypes/blank", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "jcr:uuid", "88888888-8888-8888-8888-888888888888",
            "targetLabelTemplate", "  "));
        this.mockNode("88888888-8888-8888-8888-888888888888", "/LinkTypes/blank");
        final Resource resource = this.context.create().resource("/Things/a/link:links/blank1", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", "88888888-8888-8888-8888-888888888888",
            "reference", THING_B_ID));

        assertEquals("b", resource.adaptTo(Link.class).getTargetLabel());
    }

    @Test
    void nonLinkNodesAdaptToNothing()
    {
        assertNull(Link.toLink(null));
        assertNull(Link.toLink(this.context.create().resource("/Things/c")));
    }

    @Test
    void rendersTheTargetLabelThroughTheTemplate()
    {
        this.createFixture();
        this.context.create().resource("/LinkTypes/templated", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "jcr:uuid", "33333333-3333-3333-3333-333333333333",
            "label", "Related",
            "targetLabelTemplate",
            "{typeLabel} {label}: {name} ({property:title}) from {sourceName} {bogus}{property:none}"));
        try {
            this.mockNode("33333333-3333-3333-3333-333333333333", "/LinkTypes/templated");
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
        final Resource resource = this.context.create().resource("/Things/a/link:links/t1", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", "33333333-3333-3333-3333-333333333333",
            "reference", THING_B_ID,
            "label", "extra"));

        assertEquals("Related extra: b (Thing B) from a ", resource.adaptTo(Link.class).getTargetLabel());
    }

    @Test
    void fallsBackToTheDestinationName()
    {
        final InternalLink link = (InternalLink) this.createFixture().adaptTo(Link.class);

        assertEquals("b", link.getTargetLabel());
    }

    @Test
    void reportsInaccessibleDestinations()
    {
        this.createFixture();
        // A weak reference to a deleted resource: the uuid no longer resolves
        final Resource resource = this.context.create().resource("/Things/a/link:links/broken", Map.of(
            SLING_RESOURCE_TYPE, InternalLink.RESOURCE_TYPE,
            "type", DEFINITION_ID,
            "reference", "99999999-9999-9999-9999-999999999999"));
        final InternalLink link = (InternalLink) resource.adaptTo(Link.class);

        assertNull(link.getDestination());
        assertNull(link.getBacklink());
        assertEquals("inaccessible target", link.getTargetLabel());
    }

    @Test
    void writeBehaviorNeedsTheLinksService()
    {
        // No links operations service is registered in this context, e.g. a read-only rendering context
        final InternalLink link = (InternalLink) this.createFixture().adaptTo(Link.class);

        assertFalse(link.addBacklink());
        assertFalse(link.remove(true));
    }
}
