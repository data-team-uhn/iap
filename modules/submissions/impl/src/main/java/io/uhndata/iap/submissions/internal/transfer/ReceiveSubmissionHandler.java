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
package io.uhndata.iap.submissions.internal.transfer;

import java.io.StringReader;
import java.util.Objects;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The {@code receiveSubmission} service task: rebuilds a submission another instance handed over, under the
 * submissions homepage the event targets, and records which instance it came from.
 *
 * <p>
 * The event carries the manifest as text, each binary as an attachment named after its part, and the instance it
 * came from as that instance's token said. Only the service user the endpoint for other instances runs as may send
 * it: anyone else could otherwise make a submission appear, in anyone's name, with whatever history they liked.
 * The submission is reported as created, so that the engine records the submitter it acted for as its creator.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ReceiveSubmissionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "receiveSubmission";

    /** The service user the endpoint for other instances sends this event as. */
    static final String PEER_USER = "iap-remote-peer";

    @Reference
    private SubmissionImporter importer;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        if (!PEER_USER.equals(context.getSender())) {
            throw new NotAuthorizedException("Only another instance can hand a submission over");
        }
        final WorkflowEvent event = context.getEvent();
        final String origin =
            Payloads.requireText(event, TransferFormat.ORIGIN_PARAMETER, "The instance it came from is required");
        final Resource submission = this.importer.importInto(context.getTarget(), manifest(event), part -> {
            final EventAttachment attachment = Payloads.attachment(event, part);
            return attachment == null ? null : attachment.openStream();
        });
        Objects.requireNonNull(submission.adaptTo(ModifiableValueMap.class), "A submission just made is writable")
            .put(TransferFormat.ORIGIN_PROPERTY, origin);
        context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, submission.getPath());
        // Names the instance too: the history keeps who sent the event, which is this instance's own peer user
        context.recordRole(submission, "received from " + origin);
    }

    private static JsonObject manifest(final WorkflowEvent event) throws InvalidPayloadException
    {
        final String text = Payloads.requireText(event, TransferFormat.MANIFEST_PART, "A manifest is required");
        try (JsonReader reader = Json.createReader(new StringReader(text))) {
            return reader.readObject();
        } catch (final JsonException e) {
            throw new InvalidPayloadException("The manifest is not a JSON object", e);
        }
    }
}
