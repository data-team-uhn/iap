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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.auth.token.IapToken;
import io.uhndata.iap.auth.token.TokenManager;
import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.RequestParameterAttachment;

/**
 * Where another instance hands a submission over: {@code POST /system/remote/submissions}, as a multipart request
 * carrying the {@code manifest} and one file part per binary it names.
 *
 * <p>
 * Unauthenticated as far as Sling is concerned: the token in the {@code Authorization} header is the credential. It
 * must have been issued by an instance this one trusts, and it says who sent the submission and who submitted it;
 * nothing in the body may say otherwise. What becomes of the submission is {@link SubmissionReceiver}'s to decide.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class },
    property = { "sling.auth.requirements=-" + TransferFormat.ENDPOINT_PATH })
@SlingServletPaths(value = TransferFormat.ENDPOINT_PATH)
public class SubmissionReceiverServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = 2817301469032548817L;

    /** The scheme the token is expected under, tolerated rather than required. */
    private static final String BEARER_PREFIX = "Bearer ";

    private final transient TokenManager tokenManager;

    private final transient SubmissionReceiver receiver;

    /**
     * Activate the component.
     *
     * @param tokenManager verifies the token another instance presents
     * @param receiver takes the submission in
     */
    @Activate
    public SubmissionReceiverServlet(@Reference final TokenManager tokenManager,
        @Reference final SubmissionReceiver receiver)
    {
        this.tokenManager = tokenManager;
        this.receiver = receiver;
    }

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        receive(request).write(response);
    }

    private ReceiveOutcome receive(final SlingJakartaHttpServletRequest request) throws IOException
    {
        final IapToken token = authenticate(request);
        if (token == null) {
            return ReceiveOutcome.refused(HttpServletResponse.SC_UNAUTHORIZED,
                "A valid token is required in the Authorization header");
        }
        final String email = token.getClaim(TransferFormat.EMAIL_CLAIM);
        if (email == null) {
            return ReceiveOutcome.refused(HttpServletResponse.SC_BAD_REQUEST, "The token names no submitter");
        }
        final RequestParameter manifest = request.getRequestParameter(TransferFormat.MANIFEST_PART);
        if (manifest == null || !manifest.isFormField()) {
            return ReceiveOutcome.refused(HttpServletResponse.SC_BAD_REQUEST, "A manifest is required");
        }
        // UTF-8 whatever the request says: a manifest is JSON, and the parts carry no charset of their own
        return this.receiver.receive(token.getIssuer(), email,
            manifest.getString(StandardCharsets.UTF_8.name()), files(request));
    }

    /**
     * Identify the instance behind the request from the token it presented.
     *
     * @param request the incoming request
     * @return the verified token, or {@code null} if there was none, it did not verify, or it has expired
     */
    private IapToken authenticate(final SlingJakartaHttpServletRequest request)
    {
        final String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        final String presented = header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
            ? header.substring(BEARER_PREFIX.length()) : header;
        final IapToken token = this.tokenManager.parse(presented.trim());
        // The parser rejects an expired token itself; checked again because this is the only gate in
        return token == null || token.isExpired(System.currentTimeMillis()) ? null : token;
    }

    /**
     * The binaries the request carries: every part that is a file.
     */
    private static Map<String, EventAttachment> files(final SlingJakartaHttpServletRequest request)
    {
        final Map<String, EventAttachment> files = new HashMap<>();
        for (final Map.Entry<String, RequestParameter[]> parameter : request.getRequestParameterMap().entrySet()) {
            final RequestParameter[] values = parameter.getValue();
            if (values.length == 1 && !values[0].isFormField()) {
                files.put(parameter.getKey(), new RequestParameterAttachment(values[0]));
            }
        }
        return files;
    }
}
