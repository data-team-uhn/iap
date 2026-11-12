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

import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import io.uhndata.iap.tags.models.Taggable;

/**
 * Builds the content a handler test needs: a schema version with questions, a submission for it, and documents
 * with uploads. Tests use a JCR-backed context, because the handlers write real references and binaries.
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

    private final SlingContext context;

    private int documentCount;

    SubmissionTree(final SlingContext context)
    {
        this.context = context;
        // The tags service does not run under sling-mock, so a draft is read from the node's own tags property
        context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
            final Taggable taggable = Mockito.mock(Taggable.class);
            final Answer<Boolean> isTagged = invocation -> Arrays.asList(
                resource.getValueMap().get("tags", new String[0])).contains(invocation.<String>getArgument(0));
            Mockito.when(taggable.hasOwnTag(Mockito.anyString())).thenAnswer(isTagged);
            Mockito.when(taggable.hasTag(Mockito.anyString())).thenAnswer(isTagged);
            return taggable;
        });
    }

    /** Creates the schema version, with one form to hold questions. */
    Resource createSchemaVersion()
    {
        this.context.create().resource("/Schemas/proposal", Map.of(TYPE, "sch/Schema", "title", "Proposal"));
        final Resource version = this.context.create().resource(VERSION_PATH, Map.of(TYPE, "sch/SchemaVersion",
            "version", "1.0", "tags", new String[] { "active" }));
        // Names a reading workflow, because only a schema that reads its documents has them parsed
        reference(version, SubmissionFiles.READING_WORKFLOW_PROPERTY, this.context.create().resource(READING_PATH,
            Map.of(TYPE, "wf/WorkflowVersion", "version", "1.0", "tags", new String[] { "active" })));
        // A mock repository has no /libs/sch types to inherit from, so each item sets its super type
        this.context.create().resource(FORM_PATH,
            Map.of(TYPE, "sch/FormRequirement", SUPER_TYPE, "sch/Requirement", "label", "Study"));
        return version;
    }

    /** Creates a question in the form. With a prompt, the intake reads its answer from the document. */
    Resource createQuestion(final String name, final String prompt)
    {
        return createQuestion(name, prompt, 1);
    }

    /**
     * Same as above, with the given maximum number of answers. Any maximum other than one makes the question
     * multi-valued, which decides how its extracted answer is saved.
     */
    Resource createQuestion(final String name, final String prompt, final long maxAnswers)
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

    /** Creates the submission for the schema version, still a draft. */
    Resource createSubmission()
    {
        final Resource submission = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, "sub/Submission", "title", "A proposal", "createdBy", "demo-researcher",
            "tags", new String[] { "draft" }));
        reference(submission, "schemaVersion", this.context.resourceResolver().getResource(VERSION_PATH));
        return submission;
    }

    /**
     * Creates a document with one version holding an upload.
     *
     * @param parseStatus the file's parse status, or {@code null} if it was never sent to be parsed
     * @return the {@code sub:File}
     */
    Resource createFile(final String parseStatus)
    {
        this.documentCount++;
        final String document = SUBMISSION_PATH + "/d" + this.documentCount;
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

    /**
     * Adds a newer version to the document a file belongs to, holding a parsed upload.
     *
     * @param file a file of the document
     * @return the new version's {@code sub:File}
     */
    Resource addVersion(final Resource file)
    {
        final Resource document = file.getParent().getParent();
        int versions = 0;
        for (final Resource child : document.getChildren()) {
            versions += "sub/DocumentVersion".equals(child.getResourceType()) ? 1 : 0;
        }
        final String version = document.getPath() + "/v" + (versions + 1);
        this.context.create().resource(version, Map.of(TYPE, "sub/DocumentVersion"));
        final Resource newer = this.context.create().resource(version + "/file",
            Map.of(TYPE, "sub/File", "fileName", "proposal.pdf", "parseStatus", "completed"));
        storeText(newer.getPath() + "/uploadedFile", "%PDF-1.4 the newer upload");
        return newer;
    }

    /** Creates a document requirement in the schema version. Uploads are attached for it. */
    Resource createDocumentRequirement(final String name)
    {
        return this.context.create().resource(VERSION_PATH + "/" + name,
            Map.of(TYPE, "sch/DocumentRequirement", SUPER_TYPE, "sch/Requirement", "label", name));
    }

    /**
     * Creates a parsed upload attached for a document requirement, holding the given Markdown.
     *
     * @param requirement the requirement the document fulfills
     * @param text the Markdown the parse produced
     * @return the {@code sub:File}
     */
    Resource createParsedFileFor(final Resource requirement, final String text)
    {
        final Resource file = createFile("completed");
        addMarkdown(file, text);
        reference(file.getParent().getParent(), "fulfills", requirement);
        return file;
    }

    /** Creates a document whose only version has no upload yet, and returns that version. */
    Resource createEmptyDocumentVersion()
    {
        this.documentCount++;
        final String document = SUBMISSION_PATH + "/d" + this.documentCount;
        this.context.create().resource(document, Map.of(TYPE, "sub/Document", "title", "pending"));
        return this.context.create().resource(document + "/v1", Map.of(TYPE, "sub/DocumentVersion"));
    }

    /**
     * Creates a classification requirement that reads one document requirement and picks one category. Its
     * question sits in the {@code classification} form requirement, under the same name, and the requirement
     * references it.
     *
     * @param name the requirement's name, also its question's
     * @param document the name of the document requirement it reads
     * @param prompt what the model is asked to decide
     * @param categories the values the question offers, one option each
     * @return the question the pick is saved under
     */
    Resource createClassificationQuestion(final String name, final String document, final String prompt,
        final String... categories)
    {
        final String form = VERSION_PATH + "/classification";
        if (this.context.resourceResolver().getResource(form) == null) {
            this.context.create().resource(form,
                Map.of(TYPE, "sch/FormRequirement", SUPER_TYPE, "sch/Requirement", "label", "Classification"));
        }
        final Resource question = this.context.create().resource(form + "/" + name, Map.of(TYPE, "sch/Question",
            SUPER_TYPE, "sch/FormItem", "text", "What kind of study is it?", "dataType", "text", "maxAnswers", 1L));
        for (final String category : categories) {
            this.context.create().resource(question.getPath() + "/" + category,
                Map.of(TYPE, "sch/AnswerOption", "value", category, "label", category));
        }
        final Resource requirement = this.context.create().resource(VERSION_PATH + "/" + name, Map.of(TYPE,
            "sch/ClassificationRequirement", SUPER_TYPE, "sch/Requirement", "label", "Kind of study", "prompt",
            prompt, "document", document));
        reference(requirement, "question", question);
        return question;
    }

    /**
     * Creates a validation requirement that reads one document requirement.
     *
     * @param name the requirement's name
     * @param document the name of the document requirement it reads
     * @param prompt what the model is asked to decide
     * @return the requirement
     */
    Resource createValidationRequirement(final String name, final String document, final String prompt)
    {
        return this.context.create().resource(VERSION_PATH + "/" + name, Map.of(TYPE, "sch/ValidationRequirement",
            SUPER_TYPE, "sch/Requirement", "label", "Research proposal", "prompt", prompt, "document", document));
    }

    /**
     * Creates a task of the submission's intake workflow, as the engine raises it.
     *
     * @param name the task's node name
     * @param status the task's status, such as {@code created} while it is open
     * @param performer who may complete it
     * @return the task
     */
    Resource createIntakeTask(final String name, final String status, final String performer)
    {
        final String instances = SUBMISSION_PATH + "/wf:instances";
        if (this.context.resourceResolver().getResource(instances) == null) {
            this.context.create().resource(instances, Map.of(TYPE, "wf/WorkflowInstances"));
            this.context.create().resource(instances + "/intake",
                Map.of(TYPE, "wf/WorkflowInstance", "status", "active"));
        }
        return this.context.create().resource(instances + "/intake/" + name, Map.of(TYPE, "wf/TaskInstance",
            "status", status, "performers", new String[] { performer }));
    }

    /**
     * The resource, seen through a resolver that sets each node it creates a resource type from its node type, as
     * the repository's autocreated property does. The models find validations and runs by their type.
     *
     * @param resource the resource a handler writes under
     * @return the same resource
     */
    static Resource withTypedCreates(final Resource resource)
    {
        final ResourceResolver typing = new ResourceResolverWrapper(resource.getResourceResolver())
        {
            @Override
            public Resource create(final Resource parent, final String name, final Map<String, Object> properties)
                throws PersistenceException
            {
                final Map<String, Object> typed = new HashMap<>(properties);
                if (properties.get(JcrConstants.JCR_PRIMARYTYPE) instanceof final String type
                    && type.startsWith("sub:")) {
                    typed.putIfAbsent(TYPE, type.replace(':', '/'));
                }
                return super.create(parent, name, typed);
            }
        };
        return new ResourceWrapper(resource)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return typing;
            }
        };
    }

    /** Stores the reference text that a classification or validation requirement shows the model. */
    void addTemplate(final String requirement, final String text)
    {
        storeText(VERSION_PATH + "/" + requirement + "/template", text);
    }

    void addMarkdown(final Resource file, final String text)
    {
        storeText(file.getPath() + "/markdownFile", text);
    }

    /**
     * Sets the resource type on every node a handler wrote under the submission, from its node type. The mock
     * repository does not do this, and the models need it to find the answers and runs.
     */
    void stampTypes()
    {
        stamp(this.context.resourceResolver().getResource(SUBMISSION_PATH));
    }

    private static void stamp(final Resource resource)
    {
        final String primary = resource.getValueMap().get("jcr:primaryType", String.class);
        if (primary != null && primary.startsWith("sub:") && resource.getValueMap().get(TYPE) == null) {
            resource.adaptTo(ModifiableValueMap.class).put(TYPE, primary.replace(':', '/'));
        }
        resource.getChildren().forEach(SubmissionTree::stamp);
    }

    /** Sets a JCR REFERENCE property, which the resolver cannot write. */
    static void reference(final Resource from, final String property, final Resource to)
    {
        try {
            from.adaptTo(Node.class).setProperty(property, to.adaptTo(Node.class));
            from.getResourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns the identifier that a REFERENCE to the node holds. */
    static String getIdentifier(final Resource resource)
    {
        try {
            return resource.adaptTo(Node.class).getIdentifier();
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private void storeText(final String path, final String text)
    {
        final Resource file = this.context.create().resource(path, Map.of(JcrConstants.JCR_PRIMARYTYPE, "nt:file"));
        this.context.create().resource(file.getPath() + "/jcr:content", Map.of(
            JcrConstants.JCR_PRIMARYTYPE, "nt:resource",
            "jcr:data", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }
}
