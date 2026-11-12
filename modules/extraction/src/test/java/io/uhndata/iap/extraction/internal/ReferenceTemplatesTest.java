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
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.schemas.models.Requirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ReferenceTemplates}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ReferenceTemplatesTest
{
    private final Requirement requirement = Mockito.mock(Requirement.class);

    ReferenceTemplatesTest()
    {
        Mockito.when(this.requirement.getLabel()).thenReturn("Study details");
        Mockito.when(this.requirement.getPath()).thenReturn("/Schemas/proposal/v1/study");
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

    @Test
    void readsTheTemplateUnderAHeadingNamingTheRequirement()
    {
        final Resource template = createTemplate(
            new ByteArrayInputStream("  The sample size is in Methods.\n".getBytes(StandardCharsets.UTF_8)));

        assertEquals("# Reference for \"Study details\"\n\nThe sample size is in Methods.",
            ReferenceTemplates.readTemplate(this.requirement, template, ReferenceTemplatesTest.class));
    }

    @Test
    void readsNothingWithoutATemplate()
    {
        assertNull(ReferenceTemplates.readTemplate(this.requirement, null, ReferenceTemplatesTest.class));
    }

    // The model is still worth asking without the reference, so the reading goes on and an administrator is told
    @Test
    void readsNothingFromATemplateThatCannotBeRead()
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
            assertNull(ReferenceTemplates.readTemplate(this.requirement, template, ReferenceTemplatesTest.class));
        } finally {
            ErrorLogger.unsetService(errors);
        }

        Mockito.verify(errors).logError(Mockito.any(IOException.class), Mockito.any());
    }
}
