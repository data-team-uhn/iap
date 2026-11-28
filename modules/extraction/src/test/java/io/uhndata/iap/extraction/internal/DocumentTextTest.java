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

import java.io.IOException;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link DocumentText}: reading the parsed text of a file.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DocumentTextTest
{
    private static final String PATH = "/Submissions/aa/proposal/v1/file/markdownFile";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private Resource createFileWithText(final String text)
    {
        SubmissionTree.storeText(this.context, PATH, text);
        return this.context.resourceResolver().getResource(PATH);
    }

    @Test
    void readsTheTextOfAStoredFile() throws IOException
    {
        assertEquals("# Aims\n\nTo find out.\n", DocumentText.readText(createFileWithText("# Aims\n\nTo find out.\n")));
    }

    // No parsed file gives an empty text, not an error. The caller decides what that means.
    @Test
    void readsNothingWhenThereIsNoFile() throws IOException
    {
        assertEquals("", DocumentText.readText(null));
    }

    @Test
    void readsNothingWhenTheFileHasNoContent() throws IOException
    {
        final Resource file = this.context.create().resource(PATH, Map.of("jcr:primaryType", "nt:file"));

        assertEquals("", DocumentText.readText(file));
    }

    @Test
    void readsNothingWhenTheContentHasNoBytes() throws IOException
    {
        final Resource file = this.context.create().resource(PATH, Map.of("jcr:primaryType", "nt:file"));
        this.context.create().resource(file.getPath() + "/jcr:content", Map.of("jcr:primaryType", "nt:resource"));

        assertEquals("", DocumentText.readText(file));
    }
}
