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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.utils.PrefixTree;
import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;

/**
 * Takes in a submission another instance sent, once its token has been verified: finds the submitter here by their
 * email, and hands the submission to the workflows on their behalf.
 *
 * <p>
 * Everything is done as {@value ReceiveSubmissionHandler#PEER_USER}, a service user that may act for others but
 * write nothing itself: the engine does the writing. It can read accounts, to find the submitter, and submissions
 * themselves but nothing in them, to tell a submission sent again from one that is new.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SubmissionReceiver.class)
public class SubmissionReceiver
{
    /** The subservice whose user is {@value ReceiveSubmissionHandler#PEER_USER}. */
    static final String SUBSERVICE_NAME = "remote-peer";

    private static final Logger LOGGER = LoggerFactory.getLogger(SubmissionReceiver.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private WorkflowEngine engine;

    /**
     * Takes a submission in.
     *
     * @param origin the instance that sent it, as its verified token says
     * @param email the submitter's email, as the same token says
     * @param manifest the manifest, as text
     * @param files the binaries the manifest names, by part name
     * @return what to answer the sender
     */
    ReceiveOutcome receive(final String origin, final String email, final String manifest,
        final Map<String, EventAttachment> files)
    {
        try (ResourceResolver peer = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME))) {
            final String name = SubmissionImporter.name(
                SubmissionImporter.object(TransferFormat.parse(manifest), TransferFormat.ROOT_KEY));
            final String submitter = new UserDirectory(Objects.requireNonNull(peer.adaptTo(Session.class),
                "A service session is a JCR session")).idOf(email);
            if (submitter == null) {
                return ReceiveOutcome.refused(HttpServletResponse.SC_CONFLICT,
                    "Nobody here is known by the submitter's email");
            }
            final Resource home = Objects.requireNonNull(peer.getResource(TransferFormat.HOME_PATH),
                "The submissions homepage is readable by everyone");
            final Resource existing = peer.getResource(PrefixTree.pathFor(home.getPath(), name));
            if (existing != null) {
                return origin.equals(existing.getValueMap().get(TransferFormat.ORIGIN_PROPERTY, String.class))
                    ? ReceiveOutcome.alreadyReceived(existing.getPath())
                    : ReceiveOutcome.refused(HttpServletResponse.SC_CONFLICT,
                        "A different submission by that name is already here");
            }
            final WorkflowResult result = this.engine.receiveEvent(home,
                new WorkflowEvent(TransferFormat.RECEIVE_EVENT, payload(origin, manifest, files)), submitter);
            return ReceiveOutcome.created((String) result.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
        } catch (final WorkflowException e) {
            final ReceiveOutcome outcome = ReceiveOutcome.of(e);
            if (outcome.status() == HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
                failed(origin, e);
            }
            return outcome;
        } catch (final LoginException | RepositoryException e) {
            // A missing service user, or one without the rights it needs
            failed(origin, e);
            return ReceiveOutcome.refused(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "The submission could not be taken in");
        }
    }

    /**
     * Records a failure that is this instance's to fix: nobody would otherwise find out, since all the sender sees is
     * a 500 it will try again.
     */
    private static void failed(final String origin, final Exception failure)
    {
        LOGGER.error("Could not take in a submission from {}: {}", origin, failure.getMessage(), failure);
        ErrorLogger.logError(failure, ErrorContext.of(SubmissionReceiver.class, "receive").with("origin", origin));
    }

    private static Map<String, Object> payload(final String origin, final String manifest,
        final Map<String, EventAttachment> files)
    {
        final Map<String, Object> payload = new HashMap<>(files);
        payload.put(TransferFormat.MANIFEST_PART, manifest);
        payload.put(TransferFormat.ORIGIN_PARAMETER, origin);
        return payload;
    }
}
