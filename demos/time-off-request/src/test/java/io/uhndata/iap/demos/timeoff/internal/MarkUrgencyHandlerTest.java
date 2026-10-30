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
package io.uhndata.iap.demos.timeoff.internal;

import java.time.LocalDate;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MarkUrgencyHandler}: its name, and that it judges the request it is handed against today.
 * The urgency rule itself is tested in {@link TimeOffUrgencyTest}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MarkUrgencyHandlerTest
{
    private static final String REQUEST_PATH = "/Submissions/aRequest";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final MarkUrgencyHandler handler = new MarkUrgencyHandler();

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("markTimeOffUrgency", this.handler.getName());
    }

    @Test
    void flagsTheRequestTheEventArrivedOn() throws PersistenceException
    {
        PropertyTags.installIn(this.context);
        final TimeOffRequests requests = TimeOffRequests.installIn(this.context);
        final Resource request = requests.request(REQUEST_PATH,
            Map.of("tags", new String[] {TimeOffUrgency.SUBMITTED_TAG}));
        // Tomorrow by the system clock, which is what the handler judges against
        requests.answer(request, TimeOffQuestions.START_DATE_QUESTION, LocalDate.now().plusDays(1).toString());
        final WorkflowTaskContext task = Mockito.mock(WorkflowTaskContext.class);
        Mockito.when(task.getTarget()).thenReturn(request);

        this.handler.execute(task);

        assertTrue(requests.tagsOf(REQUEST_PATH).contains(TimeOffUrgency.URGENT_TAG));
    }
}
