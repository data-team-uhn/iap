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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ExtractionFields}: which questions a newer document version leaves unread, and saving runs.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ExtractionFieldsTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private SubmissionTree tree;

    private Resource submission;

    private Resource title;

    private Resource file;

    @BeforeEach
    void setUp()
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        this.title = this.tree.createQuestion("title", "The study's full title.");
        this.file = this.tree.createParsedFileFor(this.tree.createDocumentRequirement("proposal"), "Sleep.");
    }

    private Submission getModel()
    {
        return SubmissionFiles.getSubmission(this.submission);
    }

    /** An answer to the title with one run that read the given document version. */
    private void readTitleFrom(final Resource version) throws RepositoryException, PersistenceException
    {
        final Resource answer = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer",
                "value", new String[] { "Sleep in shift workers" }));
        SubmissionTree.reference(answer, "question", this.title);
        final Resource run = this.context.create().resource(answer.getPath() + "/e1",
            Map.of("jcr:primaryType", "sub:Extraction", "sling:resourceType", "sub/Extraction",
                "extractedValues", "Sleep in shift workers", "confidence", 0.9));
        run.adaptTo(Node.class).setProperty("sources", new Value[] { this.context.resourceResolver()
            .adaptTo(Session.class).getValueFactory().createValue(version.adaptTo(Node.class)) });
        this.context.resourceResolver().commit();
    }

    // Each newer version counts on its own: one already read does not hide another one not read yet
    @Test
    void countsANewerVersionAsUnreadUntilARunCitesIt() throws Exception
    {
        final Resource newerProposal = this.tree.addVersion(this.file);
        readTitleFrom(newerProposal.getParent());
        final Resource newerConsent = this.tree.addVersion(this.tree.createFile("completed"));
        final List<Question> asked = List.of(this.title.adaptTo(Question.class));

        assertEquals(Set.of(this.title.getPath()), ExtractionFields.getUnreadInNewerVersions(getModel(), asked,
            List.of(newerProposal.adaptTo(File.class), newerConsent.adaptTo(File.class))));
        assertEquals(Set.of(), ExtractionFields.getUnreadInNewerVersions(getModel(), asked,
            List.of(newerProposal.adaptTo(File.class))), "the version already read is not new");
    }

    @Test
    void findsNothingUnreadInAFirstVersion()
    {
        assertEquals(Set.of(), ExtractionFields.getUnreadInNewerVersions(getModel(),
            List.of(this.title.adaptTo(Question.class)), List.of(this.file.adaptTo(File.class))));
    }

    // One field failing to save, such as a question edited out of the schema, must not lose the others
    @Test
    void savesTheOtherRunsWhenOneCannotBeSaved() throws Exception
    {
        final Resource sample = this.tree.createQuestion("sample", "How many participants it enrols.");
        final Map<String, Question> questions = new LinkedHashMap<>();
        questions.put("study.sample", sample.adaptTo(Question.class));
        questions.put("study.title", this.title.adaptTo(Question.class));
        this.context.resourceResolver().delete(sample);
        final ErrorLoggerService errors = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(errors);
        try {
            ExtractionFields.write(this.context.resourceResolver(), SubmissionTree.withTypedCreates(this.submission),
                getModel(), questions, Map.of(
                    "study.sample", new FieldResult("study.sample", List.of("40"), "", 0.9, List.of(), 0),
                    "study.title", new FieldResult("study.title", List.of("Sleep"), "", 0.9, List.of(), 0)),
                List.of(this.file.adaptTo(File.class)));
        } finally {
            ErrorLogger.unsetService(errors);
        }

        Mockito.verify(errors).logError(Mockito.any(PersistenceException.class), Mockito.any());
        this.tree.stampTypes();
        assertEquals(1, getModel().getAnswers().size());
    }
}
