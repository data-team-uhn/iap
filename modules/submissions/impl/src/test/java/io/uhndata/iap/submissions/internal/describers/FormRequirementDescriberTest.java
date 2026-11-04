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
package io.uhndata.iap.submissions.internal.describers;

import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.submissions.models.Submission;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FormRequirementDescriber}: a kind of form item it cannot show is left out and recorded.
 *
 * @version $Id$
 * @since 0.1.0
 */
class FormRequirementDescriberTest
{
    @Test
    void leavesOutAndRecordsAFormItemOfAKindItCannotShow()
    {
        // Neither a section nor a question, as another module could add
        final FormItem sketch = Mockito.mock(FormItem.class);
        Mockito.when(sketch.getName()).thenReturn("sketch");
        Mockito.when(sketch.getType()).thenReturn("test/Sketch");
        final FormRequirement requirement = Mockito.mock(FormRequirement.class);
        Mockito.when(requirement.getName()).thenReturn("details");
        Mockito.when(requirement.getChildren()).thenReturn(List.of(sketch));
        final Submission submission = Mockito.mock(Submission.class);
        Mockito.when(submission.getAnswersByQuestion()).thenReturn(Map.of());
        final ConditionEvaluator everything = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(everything.applies(Mockito.any(), Mockito.any())).thenReturn(true);
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);
        try {
            final JsonObjectBuilder json = Json.createObjectBuilder();

            new FormRequirementDescriber(everything).describe(requirement, submission, json);

            assertTrue(json.build().getJsonArray("items").isEmpty());
            Mockito.verify(recorder).logProblem(Mockito.eq("A form item of a kind a form cannot show"),
                Mockito.any(ErrorContext.class));
        } finally {
            ErrorLogger.unsetService(recorder);
        }
    }
}
