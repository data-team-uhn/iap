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
package io.uhndata.iap.submissions.internal;

import java.util.List;
import java.util.Objects;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.AnswerValidator;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that puts a save past every rule about what the answers may say: what the save workflow performs
 * after recording them.
 *
 * <p>
 * It runs <em>after</em> the answers are written, which is what lets each rule read the submission as the save would
 * leave it rather than reconstruct that from the payload. Nothing is lost by objecting late: a system workflow
 * commits only when it reaches its end event, and any refusal on the way there reverts the run, so the answers this
 * save wrote are discarded along with it.
 * </p>
 *
 * <p>
 * The refusal is an {@link InvalidPayloadException} because that is what it is — an answer the schema does not
 * accept — and because it is what reaches the submitter as a message on the answer they just gave, rather than as a
 * server error they can do nothing about.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ValidateAnswersHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "validateAnswers";

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, fieldOption = FieldOption.REPLACE,
        policy = ReferencePolicy.DYNAMIC)
    private volatile List<AnswerValidator> validators;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException
    {
        final Submission submission = Objects.requireNonNull(context.getTarget().adaptTo(Submission.class),
            "The save workflow only applies to submissions");
        for (final AnswerValidator validator : currentValidators()) {
            final String refusal = validator.validate(submission, context.getActor());
            if (refusal != null) {
                throw new InvalidPayloadException(refusal);
            }
        }
    }

    /**
     * The validators registered right now. A dynamic whiteboard field is null until something registers, and the
     * list itself can change under a run, so it is copied before being walked.
     */
    private List<AnswerValidator> currentValidators()
    {
        final List<AnswerValidator> current = this.validators;
        return current == null ? List.of() : List.copyOf(current);
    }
}
