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
package io.uhndata.iap.submissions.models;

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
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link Context}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ContextTest
{
    private static final String CONTEXT_PATH = "/Submissions/submission/answer/context0";

    private static final String ANSWER_PATH = "/Submissions/submission/a1";

    private static final String ANSWER_ID = "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345";

    private static final String GONE_ID = "00000000-0000-0000-0000-000000000000";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Answer.class, Context.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(CONTEXT_PATH,
            "sling:resourceType", Context.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Context.class));
    }

    @Test
    void exposesItsProperties()
    {
        final Resource resource = this.context.create().resource(CONTEXT_PATH, Map.of(
            "sling:resourceType", Context.RESOURCE_TYPE,
            "quote", "42 participants will be recruited",
            "prefix", "In total, ",
            "suffix", " over two years.",
            "header", "3.2 Recruitment",
            "page", 7L));
        final Context quoted = resource.adaptTo(Context.class);

        assertEquals("42 participants will be recruited", quoted.getQuote());
        assertEquals("In total, ", quoted.getPrefix());
        assertEquals(" over two years.", quoted.getSuffix());
        assertEquals("3.2 Recruitment", quoted.getHeader());
        assertEquals(7L, quoted.getPage());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource(CONTEXT_PATH,
            "sling:resourceType", Context.RESOURCE_TYPE);
        final Context quoted = resource.adaptTo(Context.class);

        assertNotNull(quoted);
        assertNull(quoted.getSource());
        assertNull(quoted.getQuote());
        assertNull(quoted.getPrefix());
        assertNull(quoted.getSuffix());
        assertNull(quoted.getHeader());
        // A quote from a source with no page markers, e.g. anything that came in as DOCX
        assertNull(quoted.getPage());
    }

    @Test
    void readsTheSourceItQuotes() throws RepositoryException
    {
        this.context.create().resource(ANSWER_PATH, "sling:resourceType", Answer.RESOURCE_TYPE);
        final Session session = Mockito.mock(Session.class);
        final Node answer = Mockito.mock(Node.class);
        Mockito.when(answer.getPath()).thenReturn(ANSWER_PATH);
        Mockito.when(session.getNodeByIdentifier(ANSWER_ID)).thenReturn(answer);
        Mockito.when(session.getNodeByIdentifier(GONE_ID)).thenThrow(new ItemNotFoundException(GONE_ID));
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource quoting = this.context.create().resource(CONTEXT_PATH, Map.of(
            "sling:resourceType", Context.RESOURCE_TYPE, "source", ANSWER_ID));
        assertEquals(ANSWER_PATH, quoting.adaptTo(Context.class).getSource().getPath());

        // The link is weak, so the source may have been removed since
        final Resource orphaned = this.context.create().resource(CONTEXT_PATH + "-gone", Map.of(
            "sling:resourceType", Context.RESOURCE_TYPE, "source", GONE_ID));
        assertNull(orphaned.adaptTo(Context.class).getSource());
    }
}
