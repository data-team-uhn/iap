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

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link File}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FileTest
{
    private static final String FILE_PATH = "/Submissions/submission/consent/v0/file";

    private static final String NT_FILE = "nt:file";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, File.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(FILE_PATH,
            "sling:resourceType", File.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(File.class));
    }

    @Test
    void exposesWhatTheParsingPipelineRecorded()
    {
        final Resource resource = this.context.create().resource(FILE_PATH, Map.of(
            "sling:resourceType", File.RESOURCE_TYPE,
            "parseStatus", "completed",
            "tokens", 12000L));
        final File file = resource.adaptTo(File.class);

        assertEquals("completed", file.getParseStatus());
        assertEquals(12000L, file.getTokens());
        assertNull(file.getParseError());
    }

    @Test
    void reportsAFailedParse()
    {
        final Resource resource = this.context.create().resource(FILE_PATH, Map.of(
            "sling:resourceType", File.RESOURCE_TYPE,
            "parseStatus", "failed",
            "parseError", "LibreOffice exited with 139"));
        final File file = resource.adaptTo(File.class);

        assertEquals("failed", file.getParseStatus());
        assertEquals("LibreOffice exited with 139", file.getParseError());
    }

    @Test
    void tellsTheUploadApartFromTheRenditions()
    {
        final Resource resource = this.context.create().resource(FILE_PATH,
            "sling:resourceType", File.RESOURCE_TYPE);
        this.context.create().resource(FILE_PATH + "/uploadedFile", "sling:resourceType", NT_FILE);
        this.context.create().resource(FILE_PATH + "/consent.md", "sling:resourceType", NT_FILE);
        this.context.create().resource(FILE_PATH + "/consent.pdf", "sling:resourceType", NT_FILE);
        final File file = resource.adaptTo(File.class);

        assertEquals("uploadedFile", file.getUploadedFile().getName());

        final List<Resource> renditions = file.getRenditions();

        assertEquals(2, renditions.size());
        assertEquals("consent.md", renditions.get(0).getName());
        assertEquals("consent.pdf", renditions.get(1).getName());
    }

    @Test
    void toleratesAFileNothingHasBeenDoneToYet()
    {
        final Resource resource = this.context.create().resource(FILE_PATH,
            "sling:resourceType", File.RESOURCE_TYPE);
        final File file = resource.adaptTo(File.class);

        assertNotNull(file);
        assertNull(file.getParseStatus());
        assertNull(file.getParseError());
        assertNull(file.getTokens());
        assertNull(file.getUploadedFile());
        assertTrue(file.getRenditions().isEmpty());
    }
}
