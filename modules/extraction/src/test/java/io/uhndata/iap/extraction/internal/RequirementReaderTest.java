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
package io.uhndata.iap.extraction.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link RequirementReader}: the reference text it sends with the questions.
 *
 * @version $Id$
 * @since 0.1.0
 */
class RequirementReaderTest
{
    private static final String SUBMISSION = "/Submissions/s1";

    private final Requirement requirement = Mockito.mock(Requirement.class);

    private final File file = Mockito.mock(File.class);

    private final AnswerIntakeService intake = Mockito.mock(AnswerIntakeService.class);

    private final RequirementReader reader = new RequirementReader();

    RequirementReaderTest() throws Exception
    {
        Mockito.when(this.requirement.getLabel()).thenReturn("Study details");
        Mockito.when(this.requirement.getPath()).thenReturn("/Schemas/proposal/v1/study");
        Mockito.when(this.file.getPath()).thenReturn(SUBMISSION + "/protocol/file");
        final ParsedDocuments documents = Mockito.mock(ParsedDocuments.class);
        Mockito.when(documents.scan(this.file)).thenReturn(DocumentScan.of("The study title is Sardines."));
        // DS references, which osgi-mock cannot set without the bundle's SCR metadata
        inject("intake", this.intake);
        inject("documents", documents);
        inject("runs", new ReadingRuns());
    }

    private void inject(final String name, final Object value) throws Exception
    {
        final Field field = RequirementReader.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.reader, value);
    }

    /** A template file whose stored text is the given stream. */
    private static Resource createTemplate(final InputStream data)
    {
        final Resource content = Mockito.mock(Resource.class);
        Mockito.when(content.getValueMap()).thenReturn(new ValueMapDecorator(Map.of(JcrConstants.JCR_DATA, data)));
        final Resource template = Mockito.mock(Resource.class);
        Mockito.when(template.getChild(JcrConstants.JCR_CONTENT)).thenReturn(content);
        return template;
    }

    /** Asks with the given template, and returns the reference text the model was sent. */
    private String askWithTemplate(final Resource template)
    {
        this.reader.ask(SUBMISSION, new RequirementReader.Request(this.requirement, template, "study",
            List.of(this.file), List.of(), List.of()));
        final ArgumentCaptor<String> references = ArgumentCaptor.forClass(String.class);
        Mockito.verify(this.intake).askQuestions(Mockito.eq(this.file), Mockito.any(), Mockito.any(),
            references.capture());
        return references.getValue();
    }

    @Test
    void sendsTheTemplateUnderAHeadingNamingTheRequirement()
    {
        final Resource template = createTemplate(
            new ByteArrayInputStream("  The sample size is in Methods.\n".getBytes(StandardCharsets.UTF_8)));

        assertEquals("# Reference for \"Study details\"\n\nThe sample size is in Methods.", askWithTemplate(template));
    }

    @Test
    void sendsNoReferenceWithoutATemplate()
    {
        assertNull(askWithTemplate(null));
    }

    // The model is still worth asking without the reference, so the reading goes on and an administrator is told
    @Test
    void sendsNoReferenceFromATemplateThatCannotBeRead()
    {
        final Resource template = createTemplate(new InputStream()
        {
            @Override
            public int read() throws IOException
            {
                throw new IOException("The binary is gone");
            }
        });
        final ErrorLoggerService errors = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(errors);
        try {
            assertNull(askWithTemplate(template));
        } finally {
            ErrorLogger.unsetService(errors);
        }

        Mockito.verify(errors).logError(Mockito.any(IOException.class), Mockito.any());
    }
}
