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
package io.uhndata.iap.schemas.editing.internal.handlers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.schemas.editing.internal.SchemaFixture;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SetTemplateHandler}: the upload becomes the requirement's template, replacing any before it,
 * and a generated requirement only takes a {@code .docx}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SetTemplateHandlerTest
{
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final byte[] CONTENT = { 0x50, 0x4B, 3, 4 };

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final SetTemplateHandler handler = new SetTemplateHandler();

    private SchemaFixture fixture;

    private Resource version;

    @BeforeEach
    void setUp() throws PersistenceException
    {
        this.fixture = new SchemaFixture(this.context);
        this.context.addModelsForClasses(Requirement.class, DocumentRequirement.class);
        this.version = this.fixture.version(this.fixture.schema("study"), "v1", "draft");
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(SetTemplateHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void storesTheUploadAsTheTemplate() throws WorkflowException, PersistenceException, IOException
    {
        final Resource consent = requirement("consent", false);

        this.handler.execute(task(consent, upload("consent.pdf", "application/pdf; charset=binary", CONTENT)));

        assertArrayEquals(CONTENT, templateOf("consent"));
        assertEquals("application/pdf", mimeTypeOf("consent"));
    }

    @Test
    void replacesTheTemplateAlreadyThere() throws WorkflowException, PersistenceException, IOException
    {
        final Resource consent = requirement("consent", false);
        this.handler.execute(task(consent, upload("old.pdf", "application/pdf", new byte[] { 1 })));
        this.context.resourceResolver().commit();

        this.handler.execute(task(consent, upload("new.docx", DOCX, CONTENT)));

        assertArrayEquals(CONTENT, templateOf("consent"));
        assertEquals(DOCX, mimeTypeOf("consent"));
    }

    @Test
    void readsTheTypeFromTheExtensionWhenTheBrowserDoesNotKnowIt()
        throws WorkflowException, PersistenceException, IOException
    {
        // Browsers often send Office files untyped; this is also a generated requirement, which takes only a .docx
        final Resource letter = requirement("letter", true);

        this.handler.execute(task(letter, upload("Letter.DOCX", "application/octet-stream", CONTENT)));

        assertEquals(DOCX, mimeTypeOf("letter"));

        final Resource consent = requirement("consent", false);
        this.handler.execute(task(consent, upload(null, null, CONTENT)));

        assertEquals("application/octet-stream", mimeTypeOf("consent"), "nothing better is known about it");
    }

    @Test
    void refusesAGeneratedRequirementATemplateThatIsNotADocx() throws PersistenceException
    {
        final Resource letter = requirement("letter", true);

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(task(letter, upload("letter.pdf", "application/pdf", CONTENT))));

        assertTrue(refusal.getMessage().contains("has to be a Word (.docx) file"), refusal.getMessage());
        assertNull(this.fixture.get(this.version.getPath() + "/letter/template"));
    }

    @Test
    void refusesAnEventWithoutAFile() throws PersistenceException
    {
        final Resource consent = requirement("consent", false);

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(new TaskContext(consent,
            Map.of(), Map.of())));
    }

    @Test
    void refusesAFileTooLargeToBeATemplate() throws PersistenceException
    {
        final Resource consent = requirement("consent", false);
        final EventAttachment huge = new Upload("huge.docx", DOCX, CONTENT)
        {
            @Override
            public long getSize()
            {
                return SetTemplateHandler.MAX_TEMPLATE_BYTES + 1;
            }
        };

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(task(consent, huge)));

        assertEquals("That file is 11 MB, and a template can be at most 10 MB", refusal.getMessage());
    }

    @Test
    void refusesATargetThatIsNotADocumentRequirement()
    {
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(task(this.version, upload("a.docx", DOCX, CONTENT))));
    }

    @Test
    void translatesAnUploadThatDiesWhileStoredIntoAPersistenceFailure() throws PersistenceException
    {
        final Resource consent = requirement("consent", false);
        final EventAttachment dying = new Upload("consent.pdf", "application/pdf", CONTENT)
        {
            @Override
            public InputStream openStream() throws IOException
            {
                throw new IOException("The connection was reset");
            }
        };

        assertThrows(PersistenceException.class, () -> this.handler.execute(task(consent, dying)));
    }

    private Resource requirement(final String name, final boolean generated) throws PersistenceException
    {
        final Map<String, Object> properties = new HashMap<>(Map.of("label", "The " + name));
        if (generated) {
            properties.put("generated", true);
        }
        return this.fixture.create(this.version.getPath(), name, "sch:DocumentRequirement", properties, "draft");
    }

    private static TaskContext task(final Resource target, final EventAttachment file)
    {
        return new TaskContext(target, Map.of(SetTemplateHandler.FILE_PARAMETER, file), Map.of());
    }

    private static EventAttachment upload(final String fileName, final String mimeType, final byte[] content)
    {
        return new Upload(fileName, mimeType, content);
    }

    private byte[] templateOf(final String requirement) throws IOException
    {
        this.context.resourceResolver().refresh();
        try (InputStream content = this.fixture.get(this.version.getPath() + "/" + requirement + "/template")
            .adaptTo(InputStream.class)) {
            return content.readAllBytes();
        }
    }

    private String mimeTypeOf(final String requirement)
    {
        return this.fixture.get(this.version.getPath() + "/" + requirement + "/template/jcr:content")
            .getValueMap().get("jcr:mimeType", String.class);
    }

    /** A file as an event carries it. */
    private static class Upload implements EventAttachment
    {
        private final String fileName;

        private final String mimeType;

        private final byte[] content;

        Upload(final String fileName, final String mimeType, final byte[] content)
        {
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.content = content.clone();
        }

        @Override
        public String getFileName()
        {
            return this.fileName;
        }

        @Override
        public String getMimeType()
        {
            return this.mimeType;
        }

        @Override
        public long getSize()
        {
            return this.content.length;
        }

        @Override
        public InputStream openStream() throws IOException
        {
            return new ByteArrayInputStream(this.content);
        }
    }
}
