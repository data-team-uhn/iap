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
package io.uhndata.iap.submissions.internal.generation;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.documents.generation.spi.TemplateContextProvider;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Who raised the submission a template is filled in for, as {@code submitter}: their user {@code id}, their
 * {@code name} and their {@code email}, read from their account the way notifications read them.
 *
 * <p>An account that does not record a name is called by its id, and one without an email gives an empty one, so a
 * letter is never held up by a profile nobody filled in. A submission with nobody recorded as raising it has no
 * submitter at all, and a template asking for one fails, saying so.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = TemplateContextProvider.class)
public class SubmitterTemplateValues implements TemplateContextProvider
{
    /** The name templates use for these values. */
    static final String CONTEXT_NAME = "submitter";

    @Override
    public String getName()
    {
        return CONTEXT_NAME;
    }

    @Override
    public Object getValue(final Resource target)
    {
        // Checked by type: a model adapts from any resource at all
        final Submission submission = target.isResourceType(Submission.RESOURCE_TYPE)
            ? target.adaptTo(Submission.class) : null;
        final String userId = submission == null ? null : submission.getCreatedBy();
        if (userId == null) {
            return null;
        }

        // Read what the account says about them
        final Resource account = account(target.getResourceResolver(), userId);
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", userId);
        values.put("name", account == null ? userId : account.getValueMap().get("rep:fullname", userId));
        final Resource profile = account == null ? null : account.getChild("profile");
        values.put("email", profile == null ? "" : profile.getValueMap().get("email", ""));
        return values;
    }

    /**
     * A user's account, as a resource.
     *
     * @param resolver the session to read it through
     * @param userId whose account to find
     * @return the account, or {@code null} when there is none
     * @throws IllegalStateException when the user home cannot be read, which fails the generation
     */
    private static Resource account(final ResourceResolver resolver, final String userId)
    {
        final Session session = resolver.adaptTo(Session.class);
        if (!(session instanceof JackrabbitSession jackrabbit)) {
            throw new IllegalStateException("The submitter's account cannot be looked up from this session");
        }
        try {
            final Authorizable user = jackrabbit.getUserManager().getAuthorizable(userId);
            return user == null ? null : resolver.getResource(user.getPath());
        } catch (final RepositoryException e) {
            throw new IllegalStateException("The submitter's account could not be read: " + e.getMessage(), e);
        }
    }
}
