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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that marks the reading as done. It is the only step that does, so every path of a reading
 * workflow ends with it; without it the submission would stay {@code running}, and the view would show a spinner
 * that never stops.
 *
 * <p>Only {@code running} is changed to {@code done}. A {@code failed} status is kept, so its message is not
 * lost.</p>
 *
 * <p>The step can set a {@code message}, for example to say the reading waits for the submitter. Without one, a
 * note an earlier step left is kept, such as the intake's "some questions could not be read". It can also set
 * {@code blocks} when the request cannot go on, such as when a document is not what the request asks for: the
 * submission is then marked blocked until a new reading starts.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class FinishReadingHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "finishReading";

    /** The activity property holding a message for the submitter. Optional. */
    static final String MESSAGE_PARAMETER = "message";

    /** The activity property that, when {@code true}, marks the request as unable to go on after this reading. */
    static final String BLOCKS_PARAMETER = "blocks";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws PersistenceException
    {
        final Resource target = context.getTarget();
        if (ExtractionStatus.STATUS_RUNNING.equals(
            target.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class))) {
            final String message = context.getActivity().get(MESSAGE_PARAMETER, String.class);
            // The step's own message is the deliberate one; otherwise an earlier step's note stays
            ExtractionStatus.record(target, ExtractionStatus.STATUS_DONE, message != null ? message
                : target.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
            if (Boolean.TRUE.equals(context.getActivity().get(BLOCKS_PARAMETER, Boolean.class))) {
                ExtractionStatus.markBlocked(target);
            }
        }
    }
}
