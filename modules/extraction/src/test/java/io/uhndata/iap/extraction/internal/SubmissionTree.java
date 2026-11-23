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
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.version.VersionManager;

import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.submissions.models.Answer;
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

    /** A confidence above the default threshold of 0.7, so the model's verdict or pick counts. */
    static final double SURE = 0.9;

    /** A confidence below the default threshold of 0.7, so the model's verdict or pick does not count. */
    static final double UNSURE = 0.5;

    private static final String TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private final SlingContext context;

    private int documentCount;

    private int taskCount;

    private int runCount;

    SubmissionTree(final SlingContext context)
    {
        this.context = context;
        // The tags service does not run under sling-mock, so a draft is read from the node's own tags property
        context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
            final Taggable taggable = Mockito.mock(Taggable.class);
            final org.mockito.stubbing.Answer<Boolean> isTagged = invocation -> Arrays.asList(
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
        return createQuestion(this.context.resourceResolver().getResource(FORM_PATH), name, prompt, maxAnswers);
    }

    /**
     * Creates another form in the schema version.
     *
     * @param name the form's name
     * @param label the form's label
     * @param documents the names of the document requirements it is read from
     * @return the form
     */
    Resource createForm(final String name, final String label, final String... documents)
    {
        return this.context.create().resource(VERSION_PATH + "/" + name, Map.of(TYPE, "sch/FormRequirement",
            SUPER_TYPE, "sch/Requirement", "label", label, "documents", documents));
    }

    /** Creates a question in the given form, taking one answer. */
    Resource createQuestion(final Resource form, final String name, final String prompt)
    {
        return createQuestion(form, name, prompt, 1);
    }

    private Resource createQuestion(final Resource form, final String name, final String prompt,
        final long maxAnswers)
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
        return this.context.create().resource(form.getPath() + "/" + name, properties);
    }

    /** Creates the submission for the schema version, still a draft. */
    Resource createSubmission()
    {
        final Resource submission = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, "sub/Submission", "title", "A proposal", "createdBy", "demo-researcher",
            "tags", new String[] { "draft" }));
        setReference(submission, "schemaVersion", this.context.resourceResolver().getResource(VERSION_PATH));
        return submission;
    }

    /**
     * Creates a document with one version holding an upload that was never sent to be parsed.
     *
     * @return the {@code sub:File}
     */
    Resource createUnparsedFile()
    {
        return createFile(null);
    }

    /**
     * Creates a document with one version holding an upload.
     *
     * @param parseStatus the file's parse status, such as {@code completed}
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

    /** Creates a document requirement in the schema version. Its label is its name. */
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
        setReference(file.getParent().getParent(), "fulfills", requirement);
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
        setReference(requirement, "question", question);
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
     * Saves a sure verdict on the submission, as {@code recordValidation} does.
     *
     * @param requirement the validation requirement the verdict is about
     * @param outcome {@code yes} or {@code no}
     * @return the submission's validation
     */
    Resource createVerdict(final Resource requirement, final String outcome)
    {
        return createVerdict(requirement, outcome, SURE);
    }

    /**
     * Saves a verdict on the submission, as {@code recordValidation} does.
     *
     * @param requirement the validation requirement the verdict is about
     * @param outcome {@code yes} or {@code no}
     * @param confidence how sure the model was, such as {@link #SURE} or {@link #UNSURE}
     * @return the submission's validation
     */
    Resource createVerdict(final Resource requirement, final String outcome, final double confidence)
    {
        final Resource validation = this.context.create().resource(SUBMISSION_PATH + "/" + requirement.getName()
            + "-verdict", Map.of(TYPE, "sub/Validation"));
        setReference(validation, "requirement", requirement);
        createRun(validation, confidence, outcome);
        return validation;
    }

    /**
     * Creates the submitter's answer to a question.
     *
     * @param question the question answered
     * @param values the values given; none for an answer that only holds the model's runs
     * @return the {@code sub:Answer}
     */
    Resource createAnswer(final Resource question, final String... values)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(JcrConstants.JCR_PRIMARYTYPE, "sub:Answer");
        properties.put(TYPE, "sub/Answer");
        if (values.length > 0) {
            properties.put("value", values);
        }
        final Resource answer =
            this.context.create().resource(SUBMISSION_PATH + "/a-" + question.getName(), properties);
        setReference(answer, "question", question);
        return answer;
    }

    /**
     * Creates an answer the model read and the submitter has not confirmed yet: no value, one sure run.
     *
     * @param question the question read
     * @param extracted what the model read
     * @return the {@code sub:Answer}
     */
    Resource createExtractedAnswer(final Resource question, final String extracted)
    {
        final Resource answer = createAnswer(question);
        createRun(answer, SURE, extracted);
        return answer;
    }

    /**
     * Saves a run of the model under an answer or a validation. Runs are numbered, so the last one made is the
     * newest.
     *
     * @param parent the answer or validation
     * @param confidence how sure the model was
     * @param values what it read; none for a run that found nothing
     * @return the {@code sub:Extraction}
     */
    Resource createRun(final Resource parent, final double confidence, final String... values)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(JcrConstants.JCR_PRIMARYTYPE, "sub:Extraction");
        properties.put(TYPE, "sub/Extraction");
        properties.put("confidence", confidence);
        if (values.length > 0) {
            properties.put("extractedValues", values);
        }
        return this.context.create().resource(parent.getPath() + "/run" + ++this.runCount, properties);
    }

    /**
     * Finds the answer to a question, as the models read it.
     *
     * @param question the question's path
     * @return the answer, or {@code null} when there is none
     */
    Answer findAnswer(final String question)
    {
        return SubmissionFiles.getSubmission(this.context.resourceResolver().getResource(SUBMISSION_PATH))
            .getAnswers().stream()
            .filter(answer -> answer.getQuestion() != null && question.equals(answer.getQuestion().getPath()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Marks the submission as being read by a job: the status running, and the claim held by the job.
     *
     * @param jobId the job that holds the claim
     */
    void startReadingAs(final String jobId)
    {
        final ModifiableValueMap properties = this.context.resourceResolver().getResource(SUBMISSION_PATH)
            .adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_RUNNING);
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, jobId);
    }

    /**
     * Sets the documents a form is read from.
     *
     * @param form the form's path
     * @param documents the names of the document requirements; none to read the form from no document
     */
    void setFormDocuments(final String form, final String... documents)
    {
        final ModifiableValueMap properties =
            this.context.resourceResolver().getResource(form).adaptTo(ModifiableValueMap.class);
        if (documents.length == 0) {
            properties.remove("documents");
        } else {
            properties.put("documents", documents);
        }
    }

    /**
     * Creates a task of the submission's intake workflow, as the engine raises it.
     *
     * @param name the task's node name, and its task definition id: the name of the validation it waits for
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
        // Numbered, as the engine names task nodes: an intake may hold the same task more than once
        return this.context.create().resource(instances + "/intake/" + name + ++this.taskCount,
            Map.of(TYPE, "wf/TaskInstance",
                "status", status, "performers", new String[] { performer }, "taskDefinitionId", name));
    }

    /**
     * Wraps the resource so each node created under it gets a resource type from its node type, as Oak's
     * autocreated property would. The models find validations and runs by their type.
     *
     * @param resource the resource a handler writes under
     * @return the same resource
     */
    static Resource wrapWithTypedCreates(final Resource resource)
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

    /**
     * The resource as a caller sees it who may not write it: it gives no {@link ModifiableValueMap}.
     *
     * @param resource the resource
     * @return the same resource, read-only
     */
    static Resource wrapReadOnly(final Resource resource)
    {
        return new ResourceWrapper(resource)
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return ModifiableValueMap.class.equals(type) ? null : super.adaptTo(type);
            }
        };
    }

    /**
     * The resource, seen through a resolver whose every resource is read-only, for a handler that looks up the
     * nodes it writes through its resolver.
     *
     * @param resource the resource
     * @return the same resource, with a read-only resolver
     */
    static Resource wrapResolverReadOnly(final Resource resource)
    {
        final ResourceResolver readOnly = new ResourceResolverWrapper(resource.getResourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource found = super.getResource(path);
                return found == null ? null : wrapReadOnly(found);
            }
        };
        return new ResourceWrapper(resource)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return readOnly;
            }
        };
    }

    /**
     * The resource as a checked-in versionable node: a handler must check it out before it writes.
     *
     * @param resource the resource
     * @param versions what the node's checkout goes through, so a test can see it asked or make it fail
     * @return the same resource, checked in
     */
    static Resource wrapCheckedIn(final Resource resource, final VersionManager versions)
    {
        final Node node = Mockito.mock(Node.class);
        final Session session = Mockito.mock(Session.class);
        final Workspace workspace = Mockito.mock(Workspace.class);
        try {
            Mockito.when(node.isNodeType(JcrConstants.MIX_VERSIONABLE)).thenReturn(true);
            Mockito.when(node.getPath()).thenReturn(resource.getPath());
            Mockito.when(node.getSession()).thenReturn(session);
            Mockito.when(session.getWorkspace()).thenReturn(workspace);
            Mockito.when(workspace.getVersionManager()).thenReturn(versions);
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
        return new ResourceWrapper(resource)
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return Node.class.equals(type) ? type.cast(node) : super.adaptTo(type);
            }
        };
    }

    /** Stores the reference text a requirement or a form shows the model. */
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
    static void setReference(final Resource from, final String property, final Resource to)
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
        storeText(this.context, path, text);
    }

    /**
     * Stores a text as a file node: an {@code nt:file} whose {@code jcr:content} holds the text as its data.
     *
     * @param context the test's context
     * @param path where the file goes
     * @param text what it holds
     */
    static void storeText(final SlingContext context, final String path, final String text)
    {
        storeText(context, path, text, Map.of());
    }

    /**
     * Same as above, with the time the repository records the text was written, which tells one parse from the
     * next.
     *
     * @param context the test's context
     * @param path where the file goes
     * @param text what it holds
     * @param writtenAt when it was written
     */
    static void storeText(final SlingContext context, final String path, final String text, final Calendar writtenAt)
    {
        storeText(context, path, text, Map.of(JcrConstants.JCR_LASTMODIFIED, writtenAt));
    }

    private static void storeText(final SlingContext context, final String path, final String text,
        final Map<String, Object> more)
    {
        final Resource file = context.create().resource(path, Map.of(JcrConstants.JCR_PRIMARYTYPE, "nt:file"));
        final Map<String, Object> content = new HashMap<>(more);
        content.put(JcrConstants.JCR_PRIMARYTYPE, "nt:resource");
        content.put(JcrConstants.JCR_DATA, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        context.create().resource(file.getPath() + "/" + JcrConstants.JCR_CONTENT, content);
    }
}
