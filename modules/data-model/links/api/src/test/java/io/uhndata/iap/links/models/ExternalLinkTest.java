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

import java.util.HashMap;
import java.util.Map;

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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ExternalLink}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ExternalLinkTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String DEFINITION_ID = "11111111-1111-1111-1111-111111111111";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
        throws RepositoryException
    {
        this.context.addModelsForClasses(Content.class, LinkDefinition.class, ResourceLink.class,
            ExternalLink.class);
        final Session session = Mockito.mock(Session.class);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPath()).thenReturn("/LinkTypes/ehrChart");
        Mockito.when(session.getNodeByIdentifier(DEFINITION_ID)).thenReturn(node);
        // Unknown identifiers throw, like on a real repository, instead of returning null
        Mockito.when(session.getNodeByIdentifier("99999999-9999-9999-9999-999999999999"))
            .thenThrow(new javax.jcr.ItemNotFoundException());
    }

    private Resource createFixture(final Map<String, Object> definitionSettings)
    {
        final Map<String, Object> definition = new HashMap<>(Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "jcr:uuid", DEFINITION_ID,
            "external", true));
        definition.putAll(definitionSettings);
        this.context.create().resource("/LinkTypes/ehrChart", definition);
        this.context.create().resource("/Things/a");
        return this.context.create().resource("/Things/a/iap:links/e1", Map.of(
            SLING_RESOURCE_TYPE, ExternalLink.RESOURCE_TYPE,
            "type", DEFINITION_ID,
            "value", "12345"));
    }

    @Test
    void valuelessLinksHaveNoUrlAndAnEmptyLabel()
    {
        this.createFixture(Map.of("urlTemplate", "https://ehr.example.org/chart/{value}"));
        final Resource valueless = this.context.create().resource("/Things/a/iap:links/e2", Map.of(
            SLING_RESOURCE_TYPE, ExternalLink.RESOURCE_TYPE,
            "type", DEFINITION_ID));
        final ExternalLink link = (ExternalLink) valueless.adaptTo(Link.class);

        assertNull(link.getTargetUrl());
        assertEquals("", link.getTargetLabel());
    }

    @Test
    void linksWithUnresolvableDefinitionsStillDisplay()
    {
        this.createFixture(Map.of("urlTemplate", "https://ehr.example.org/chart/{value}"));
        final Resource untyped = this.context.create().resource("/Things/a/iap:links/e3", Map.of(
            SLING_RESOURCE_TYPE, ExternalLink.RESOURCE_TYPE,
            "type", "99999999-9999-9999-9999-999999999999",
            "value", "677"));
        final ExternalLink link = (ExternalLink) untyped.adaptTo(Link.class);

        assertNull(link.getTargetUrl());
        assertEquals("677", link.getTargetLabel());
    }

    @Test
    void dispatchesToTheConcreteModel()
    {
        final Link link = this.createFixture(Map.of()).adaptTo(Link.class);

        assertEquals(ExternalLink.class, link.getClass());
        assertEquals("12345", ((ExternalLink) link).getValue());
    }

    @Test
    void rendersTheTargetUrl()
    {
        final ExternalLink link = (ExternalLink) this
            .createFixture(Map.of("urlTemplate", "https://ehr.example.org/chart/{value}")).adaptTo(Link.class);

        assertEquals("https://ehr.example.org/chart/12345", link.getTargetUrl());
    }

    @Test
    void hasNoUrlWithoutATemplate()
    {
        final ExternalLink link = (ExternalLink) this.createFixture(Map.of()).adaptTo(Link.class);

        assertNull(link.getTargetUrl());
    }

    @Test
    void usesTheValueAsDefaultLabel()
    {
        final ExternalLink link = (ExternalLink) this.createFixture(Map.of()).adaptTo(Link.class);

        assertEquals("12345", link.getTargetLabel());
    }

    @Test
    void resolvesTheValuePlaceholderInTemplates()
    {
        final ExternalLink link = (ExternalLink) this
            .createFixture(Map.of("label", "EHR chart", "resourceLabelTemplate", "{typeLabel} #{value}{name}"))
            .adaptTo(Link.class);

        assertEquals("EHR chart #12345", link.getTargetLabel());
    }
}
