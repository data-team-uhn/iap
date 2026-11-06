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

import java.io.IOException;
import java.util.Calendar;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.jcr.Binary;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.auth.token.IapToken;
import io.uhndata.iap.auth.token.TokenManager;
import io.uhndata.iap.httprequests.api.HttpRequests;
import io.uhndata.iap.httprequests.api.Multipart;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The {@code transferSubmission} service task: sends the target submission to the instance the event names, which
 * takes it in on behalf of its submitter.
 *
 * <p>
 * The destination must be an instance this one trusts, so that an event cannot send a submission anywhere. The
 * other instance knows the submitter by their email, which the token it is sent with carries, signed. Nothing is
 * changed here: the steps after this one tag and archive the submission, and they only run once the other instance
 * has taken it. An answer saying it already has this submission from here counts as taking it, so that sending
 * again after a run that failed once the other instance had committed finishes the job.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class TransferSubmissionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "transferSubmission";

    /** The payload entry naming the instance to send to, as its identity. */
    private static final String DESTINATION_PARAMETER = "destination";

    /** The token claim carrying the submitter's email. */
    private static final String EMAIL_CLAIM = "email";

    /** How long the token is good for: long enough to arrive, too short to be worth stealing. */
    private static final int TOKEN_MINUTES = 5;

    @Reference
    private SubmissionExporter exporter;

    @Reference
    private TokenManager tokens;

    @Reference
    private HttpRequests http;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException
    {
        final String destination =
            Payloads.requireText(context.getEvent(), DESTINATION_PARAMETER, "A destination is required");
        if (!this.tokens.isTrustedPeer(destination)) {
            throw new InvalidPayloadException(destination + " is not an instance this one trusts");
        }
        final Resource submission = context.getTarget();
        final String submitter = submission.getValueMap().get(TransferFormat.CREATED_BY_PROPERTY, String.class);
        if (submitter == null) {
            throw new InvalidStateException("The submission records no submitter to hand it over for");
        }
        try {
            final String email = emailOf(submission, submitter);
            final SubmissionExport export = this.exporter.export(submission);
            final IapToken token = this.tokens.create(submitter, expiry(), Map.of(EMAIL_CLAIM, email),
                Set.of(destination));
            if (token == null) {
                throw new WorkflowFailedException("This instance cannot issue tokens");
            }
            TransferAnswer.read(destination, this.http.post(endpoint(destination),
                Map.of("Authorization", "Bearer " + token.getToken()), body(export)));
        } catch (final RepositoryException e) {
            throw new WorkflowFailedException("Could not read the submission to send", e);
        } catch (final IOException e) {
            throw new WorkflowFailedException("Could not send the submission to " + destination, e);
        }
    }

    /**
     * The email the other instance will know the submitter by.
     */
    private static String emailOf(final Resource submission, final String submitter)
        throws WorkflowException, RepositoryException
    {
        final Session session = Objects.requireNonNull(submission.getResourceResolver().adaptTo(Session.class),
            "The engine's session is a JCR session");
        final String email = new UserDirectory(session).emailOf(submitter);
        if (email == null) {
            throw new InvalidStateException("The submitter has no email for another instance to know them by");
        }
        return email;
    }

    private static Calendar expiry()
    {
        final Calendar expiry = Calendar.getInstance();
        expiry.add(Calendar.MINUTE, TOKEN_MINUTES);
        return expiry;
    }

    /**
     * Where the destination takes submissions in. An identity is often a base URL, with or without a final slash.
     */
    private static String endpoint(final String destination)
    {
        final String base = destination.endsWith("/") ? destination.substring(0, destination.length() - 1)
            : destination;
        return base + TransferFormat.ENDPOINT_PATH;
    }

    /**
     * The manifest, then each binary as a file of its own, which is what makes the receiving end take it as one.
     */
    private static Multipart body(final SubmissionExport export)
    {
        final Multipart body = new Multipart()
            .add(TransferFormat.MANIFEST_PART, export.manifest().toString(), "application/json");
        for (final Map.Entry<String, Binary> binary : export.binaries().entrySet()) {
            final Binary content = binary.getValue();
            body.add(binary.getKey(), binary.getKey(), "application/octet-stream", () -> {
                try {
                    return content.getStream();
                } catch (final RepositoryException e) {
                    throw new IOException("Could not read a binary of the submission", e);
                }
            });
        }
        return body;
    }
}
