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
package io.uhndata.iap.submissions.internal.handlers;

import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the shipped preview workflow, as the bundle installs it. Its nodes are what the engine runs, not its
 * diagram, so a property only the diagram has does nothing.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class GenerateDocumentWorkflowTest
{
    private static final String INSTALLED = "/SystemWorkflows/generateDocument";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.load().json("/SLING-INF/content/SystemWorkflows/generateDocument.json", INSTALLED);
    }

    @Test
    void itsStepIsAPreviewOfTheRequirementTheEventNames()
    {
        final ValueMap step = this.context.resourceResolver().getResource(INSTALLED + "/v1/generate").getValueMap();

        assertEquals(GenerateDocumentHandler.HANDLER_NAME, step.get("handler", String.class));
        assertTrue(Boolean.parseBoolean(step.get(GenerateDocumentHandler.PREVIEW_PARAMETER, String.class)),
            "without it, the step reads as a schema's own, which must name its requirement");
    }

    @Test
    void onlyTheSubmitterIsOfferedIt()
    {
        final ValueMap start = this.context.resourceResolver().getResource(INSTALLED + "/v1/requested").getValueMap();

        assertArrayEquals(new String[] { "@creator" }, start.get("performers", String[].class));
    }
}
