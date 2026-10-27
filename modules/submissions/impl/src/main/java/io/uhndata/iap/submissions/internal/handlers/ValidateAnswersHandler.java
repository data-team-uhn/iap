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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.AnswerValidator;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that puts a save past every registered {@link AnswerValidator}, once the answers are recorded.
 *
 * <p>It runs after the answers are written, so each rule reads what the save recorded. A system workflow commits
 * only at its end event, so a refusal reverts the whole run, answers included.</p>
 *
 * <p>A refusal is an {@link InvalidPayloadException}, which the servlet answers as a 400 carrying the message, and
 * the editor shows on the answer just given.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ValidateAnswersHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "validateAnswers";

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private volatile List<AnswerValidator> validators;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException
    {
        final Submission submission = SubmissionHost.read(ExecutionHost.of(context), HANDLER_NAME);
        final Optional<String> refusal = this.validators.stream()
            .map(validator -> validator.validate(submission, context.getActor()))
            .filter(Objects::nonNull)
            .findFirst();
        if (refusal.isPresent()) {
            throw new InvalidPayloadException(refusal.get());
        }
    }
}
