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

import java.util.Map;
import java.util.Objects;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.submissions.models.Submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link SubmitterTemplateValues}: who raised a submission, as their account tells it, with a
 * missing name or email never holding up a letter, and an account that cannot be looked up failing it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SubmitterTemplateValuesTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aStudy";

    private static final String RESEARCHER = "demo-researcher";

    private static final String HOME = "/home/users/d/demo-researcher";

    private final SlingContext context = new SlingContext();

    private final SubmitterTemplateValues values = new SubmitterTemplateValues();

    private final UserManager users = Mockito.mock(UserManager.class);

    private JackrabbitSession session;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.context.addModelsForClasses(Content.class, Entity.class, Submission.class);
        this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A study", "createdBy", RESEARCHER));
        this.session = Mockito.mock(JackrabbitSession.class);
        Mockito.when(this.session.getUserManager()).thenReturn(this.users);
    }

    @Test
    void isCalledSubmitter()
    {
        assertEquals("submitter", this.values.getName());
    }

    @Test
    void givesTheNameAndEmailTheAccountRecords() throws RepositoryException
    {
        account(Map.of("rep:fullname", "Dr. Ada Example"));
        this.context.create().resource(HOME + "/profile", Map.of("email", "ada@example.org"));

        assertEquals(Map.of("id", RESEARCHER, "name", "Dr. Ada Example", "email", "ada@example.org"),
            this.values.getValue(submission(this.session)));
    }

    @Test
    void callsSomeoneWhoseAccountRecordsNoNameByTheirIdAndGivesNoEmail() throws RepositoryException
    {
        account(Map.of());

        assertEquals(Map.of("id", RESEARCHER, "name", RESEARCHER, "email", ""),
            this.values.getValue(submission(this.session)));
    }

    @Test
    void callsSomeoneWithNoAccountHereByTheirId()
    {
        assertEquals(Map.of("id", RESEARCHER, "name", RESEARCHER, "email", ""),
            this.values.getValue(submission(this.session)));
    }

    @Test
    void hasNobodyForASubmissionNobodyIsRecordedAsRaising()
    {
        this.context.create().resource("/Submissions/orphan", Map.of(TYPE, Submission.RESOURCE_TYPE, "title", "?"));

        assertNull(this.values.getValue(Objects.requireNonNull(
            this.context.resourceResolver().getResource("/Submissions/orphan"))));
    }

    @Test
    void hasNobodyForWhatIsNotASubmission()
    {
        assertNull(this.values.getValue(this.context.create().resource("/Schemas/study")));
    }

    @Test
    void aSessionThatCannotLookUpAccountsFailsTheGeneration()
    {
        final Session plain = Mockito.mock(Session.class);

        assertThrows(IllegalStateException.class, () -> this.values.getValue(submission(plain)));
    }

    @Test
    void anAccountThatCannotBeReadFailsTheGeneration() throws RepositoryException
    {
        Mockito.when(this.users.getAuthorizable(RESEARCHER)).thenThrow(new RepositoryException("user home is gone"));

        assertThrows(IllegalStateException.class, () -> this.values.getValue(submission(this.session)));
    }

    private void account(final Map<String, Object> properties) throws RepositoryException
    {
        this.context.create().resource(HOME, properties);
        final Authorizable user = Mockito.mock(Authorizable.class);
        Mockito.when(user.getPath()).thenReturn(HOME);
        Mockito.when(this.users.getAuthorizable(RESEARCHER)).thenReturn(user);
    }

    /** The submission, read through a resolver that adapts to the given session. */
    private Resource submission(final Session asSession)
    {
        final ResourceResolver resolver = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public <A> A adaptTo(final Class<A> type)
            {
                return Session.class.equals(type) ? type.cast(asSession) : super.adaptTo(type);
            }
        };
        return new ResourceWrapper(Objects.requireNonNull(this.context.resourceResolver().getResource(SUBMISSION_PATH)))
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }
        };
    }
}
