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

import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link FinishReadingHandler}: the reading is over, and the view has to be able to tell.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FinishReadingHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final FinishReadingHandler handler = new FinishReadingHandler();

    private Resource submission;

    @BeforeEach
    void setUp()
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.schemaVersion();
        this.submission = tree.submission();
    }

    private void statusIs(final String status, final String message)
    {
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.PROPERTY, status);
        if (message != null) {
            properties.put(ExtractionStatus.MESSAGE, message);
        }
    }

    private String property(final String name)
    {
        return this.submission.getValueMap().get(name, String.class);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("finishReading", this.handler.getName());
    }

    // The case this step exists for: no category was picked, so neither set of questions applied and nothing
    // else would ever have moved the submission off `running`
    @Test
    void endsAReadingThatAskedNothing() throws Exception
    {
        statusIs(ExtractionStatus.RUNNING, null);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals("done", property(ExtractionStatus.PROPERTY));
        assertNull(property(ExtractionStatus.MESSAGE));
    }

    // A step that already said how the reading ended knew something this one does not
    @Test
    void leavesAReadingThatAlreadyFailedAlone() throws Exception
    {
        statusIs(ExtractionStatus.FAILED, "The model's answer could not be read");

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals("failed", property(ExtractionStatus.PROPERTY));
        assertEquals("The model's answer could not be read", property(ExtractionStatus.MESSAGE));
    }

    @Test
    void leavesAReadingThatAlreadyFinishedAlone() throws Exception
    {
        statusIs(ExtractionStatus.DONE, "The schema asks nothing of the document");

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals("The schema asks nothing of the document", property(ExtractionStatus.MESSAGE));
    }

    @Test
    void doesNothingForASubmissionNoReadingEverStarted() throws Exception
    {
        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertNull(property(ExtractionStatus.PROPERTY));
    }
}
