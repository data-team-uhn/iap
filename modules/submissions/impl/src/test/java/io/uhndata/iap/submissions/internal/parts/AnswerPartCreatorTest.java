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
package io.uhndata.iap.submissions.internal.parts;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.submissions.internal.CompletenessFixture;

import static io.uhndata.iap.submissions.internal.CompletenessFixture.NOTE;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.REASON;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.START_DATE;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.SUBMISSION_PATH;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.VERSION_PATH;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.answer;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link AnswerPartCreator}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerPartCreatorTest
{
    /** The schema parts these tests hide; everything else applies. */
    private final Set<String> hidden = new HashSet<>();

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final AnswerPartCreator creator = new AnswerPartCreator();

    @BeforeEach
    void setUp() throws Exception
    {
        CompletenessFixture.inject(this.creator, "conditions", CompletenessFixture.setUp(this.context, this.hidden));
    }

    @Test
    void givesEveryShownQuestionAnEmptyAnswer() throws Exception
    {
        this.creator.createMissing(submission(this.context));

        assertEquals(List.of(path(NOTE), path(START_DATE), path(REASON)), answered());
    }

    @Test
    void fillsNoGapTwice() throws Exception
    {
        answer(this.context, START_DATE, "2026-10-06");

        this.creator.createMissing(submission(this.context));

        assertEquals(List.of(path(NOTE), path(START_DATE), path(REASON)), answered());
    }

    @Test
    void givesNothingUnderASectionThatDoesNotApply() throws Exception
    {
        this.hidden.add("why");

        this.creator.createMissing(submission(this.context));

        assertEquals(List.of(path(NOTE), path(START_DATE)), answered());
    }

    @Test
    void keepsAnAnswerWhoseQuestionStoppedApplying() throws Exception
    {
        answer(this.context, REASON, "A wedding");
        this.hidden.add(CompletenessFixture.DETAILS);

        this.creator.createMissing(submission(this.context));

        assertEquals(List.of(path(REASON)), answered());
    }

    @Test
    void checksOutASubmissionBeforeGivingItAnAnswer() throws Exception
    {
        final Node node = Mockito.mock(Node.class);
        final VersionManager versions = Mockito.mock(VersionManager.class);
        final Workspace workspace = Mockito.mock(Workspace.class);
        final Session session = Mockito.mock(Session.class);
        Mockito.when(node.isNodeType("mix:versionable")).thenReturn(true);
        Mockito.when(node.getPath()).thenReturn(SUBMISSION_PATH);
        Mockito.when(node.getSession()).thenReturn(session);
        Mockito.when(session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getVersionManager()).thenReturn(versions);
        final Resource submission = submission(this.context, node);

        this.creator.createMissing(submission);

        Mockito.verify(versions, Mockito.atLeastOnce()).checkout(SUBMISSION_PATH);
    }

    private List<String> answered()
    {
        return CompletenessFixture.answeredQuestions(this.context);
    }

    private static String path(final String question)
    {
        return VERSION_PATH + "/" + question;
    }
}
