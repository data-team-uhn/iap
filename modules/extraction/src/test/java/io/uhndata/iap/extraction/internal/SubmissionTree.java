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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import io.uhndata.iap.tags.models.Taggable;

/**
 * Builds the repository a handler test needs: a schema version with questions, a submission answering it, and
 * documents with uploads under it. JCR-backed, because the handlers write real references and binaries.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SubmissionTree
{
    static final String VERSION_PATH = "/Schemas/proposal/v1";

    static final String FORM_PATH = VERSION_PATH + "/study";

    static final String SUBMISSION_PATH = "/Submissions/aa/bb/cc/proposal-1";

    static final String READING_PATH = "/Workflows/readProposal/v1";

    private static final String TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private final SlingContext context;

    private int documents;

    SubmissionTree(final SlingContext context)
    {
        this.context = context;
        // The tags service does not run under sling-mock; a draft is read off the node's own tags
        context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
            final Taggable taggable = Mockito.mock(Taggable.class);
            final Answer<Boolean> isTagged = invocation -> Arrays.asList(
                resource.getValueMap().get("tags", new String[0])).contains(invocation.<String>getArgument(0));
            Mockito.when(taggable.hasOwnTag(Mockito.anyString())).thenAnswer(isTagged);
            Mockito.when(taggable.hasTag(Mockito.anyString())).thenAnswer(isTagged);
            return taggable;
        });
    }

    /** The schema version, with one form to hold questions. */
    Resource schemaVersion()
    {
        this.context.create().resource("/Schemas/proposal", Map.of(TYPE, "sch/Schema", "title", "Proposal"));
        final Resource version = this.context.create().resource(VERSION_PATH, Map.of(TYPE, "sch/SchemaVersion",
            "version", "1.0", "tags", new String[] {"active"}));
        // Names a reading workflow, since only a schema that reads its documents has them parsed
        reference(version, ParseDocumentsHandler.READING_WORKFLOW, this.context.create().resource(READING_PATH,
            Map.of(TYPE, "wf/WorkflowVersion", "version", "1.0", "tags", new String[] {"active"})));
        // A mock repository has no /libs/sch hierarchy to inherit from, so each item carries its super type
        this.context.create().resource(FORM_PATH,
            Map.of(TYPE, "sch/FormRequirement", SUPER_TYPE, "sch/Requirement", "label", "Study"));
        return version;
    }

    /** A question in the form; with a prompt, one the intake reads out of the document. */
    Resource question(final String name, final String prompt)
    {
        return question(name, prompt, 1);
    }

    /**
     * The same, taking as many answers as given. A maximum other than one is what makes a question
     * multi-valued, which is what decides how its extracted answer is stored.
     */
    Resource question(final String name, final String prompt, final long maxAnswers)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(TYPE, "sch/Question");
        properties.put(SUPER_TYPE, "sch/FormItem");
        properties.put("text", "What is the " + name + "?");
        properties.put("dataType", "text");
        properties.put("maxAnswers", maxAnswers);
        if (prompt != null) {
            properties.put("extractionPrompt", prompt);
        }
        return this.context.create().resource(FORM_PATH + "/" + name, properties);
    }

    /** The submission, answering the schema version, still a draft. */
    Resource submission()
    {
        final Resource submission = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, "sub/Submission", "title", "A proposal", "createdBy", "demo-researcher",
            "tags", new String[] { "draft" }));
        reference(submission, "schemaVersion", this.context.resourceResolver().getResource(VERSION_PATH));
        return submission;
    }

    /**
     * A document with one version holding an upload.
     *
     * @param parseStatus where its parse got to, or {@code null} for one never sent
     * @return the {@code sub:File}
     */
    Resource file(final String parseStatus)
    {
        this.documents++;
        final String document = SUBMISSION_PATH + "/d" + this.documents;
        this.context.create().resource(document, Map.of(TYPE, "sub/Document", "title", "proposal.pdf"));
        this.context.create().resource(document + "/v1", Map.of(TYPE, "sub/DocumentVersion"));
        final Map<String, Object> properties = new HashMap<>();
        properties.put(TYPE, "sub/File");
        properties.put("fileName", "proposal.pdf");
        if (parseStatus != null) {
            properties.put("parseStatus", parseStatus);
        }
        final Resource file = this.context.create().resource(document + "/v1/file", properties);
        storeText(file.getPath() + "/uploadedFile", "%PDF-1.4 the upload");
        return file;
    }

    /** A document requirement of the schema version, which uploads are attached for. */
    Resource documentRequirement(final String name)
    {
        return this.context.create().resource(VERSION_PATH + "/" + name,
            Map.of(TYPE, "sch/DocumentRequirement", SUPER_TYPE, "sch/Requirement", "label", name));
    }

    /**
     * A parsed upload attached for a document requirement, holding the given Markdown.
     *
     * @param requirement the requirement the document fulfills
     * @param text what the parse produced
     * @return the {@code sub:File}
     */
    Resource parsedFor(final Resource requirement, final String text)
    {
        final Resource file = file("completed");
        markdown(file, text);
        reference(file.getParent().getParent(), "fulfills", requirement);
        return file;
    }

    /** A document whose version holds no upload yet. */
    Resource emptyDocument()
    {
        this.documents++;
        final String document = SUBMISSION_PATH + "/d" + this.documents;
        this.context.create().resource(document, Map.of(TYPE, "sub/Document", "title", "pending"));
        return this.context.create().resource(document + "/v1", Map.of(TYPE, "sub/DocumentVersion"));
    }

    /**
     * A yes/no classification requirement reading one document requirement, with its one question.
     *
     * @param name the requirement's name
     * @param document the name of the document requirement it reads
     * @param prompt what the model is asked to decide
     * @return the question the pick is stored under
     */
    Resource classification(final String name, final String document, final String prompt)
    {
        final String path = VERSION_PATH + "/" + name;
        this.context.create().resource(path, Map.of(TYPE, "sch/ClassificationRequirement", SUPER_TYPE,
            "sch/Requirement", "label", "Is it one?", "prompt", prompt, "document", document));
        final Resource question = this.context.create().resource(path + "/decision", Map.of(TYPE, "sch/Question",
            SUPER_TYPE, "sch/FormItem", "text", "Is it one?", "dataType", "text", "maxAnswers", 1L));
        this.context.create().resource(path + "/decision/yes", Map.of(TYPE, "sch/AnswerOption", "value", "yes",
            "label", "Yes"));
        this.context.create().resource(path + "/decision/no", Map.of(TYPE, "sch/AnswerOption", "value", "no",
            "label", "No"));
        return question;
    }

    /** The reference text a classification requirement shows the model. */
    void template(final String requirement, final String text)
    {
        storeText(VERSION_PATH + "/" + requirement + "/template", text);
    }

    void markdown(final Resource file, final String text)
    {
        storeText(file.getPath() + "/markdownFile", text);
    }

    /** A JCR REFERENCE, which the resolver cannot write. */
    static void reference(final Resource from, final String property, final Resource to)
    {
        try {
            from.adaptTo(Node.class).setProperty(property, to.adaptTo(Node.class));
            from.getResourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The identifier a REFERENCE to a node holds. */
    static String identifierOf(final Resource resource)
    {
        try {
            return resource.adaptTo(Node.class).getIdentifier();
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private void storeText(final String path, final String text)
    {
        final Resource file = this.context.create().resource(path, Map.of(PRIMARY_TYPE, "nt:file"));
        this.context.create().resource(file.getPath() + "/jcr:content", Map.of(
            PRIMARY_TYPE, "nt:resource",
            "jcr:data", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }
}
