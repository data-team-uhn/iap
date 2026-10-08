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
package io.uhndata.iap.submissions.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.Submission;

/**
 * A submission and the schema it answers, for the tests of what a submission is still missing: a form asking for a
 * date, an optional note, and a reason one section down, and a document requirement.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class CompletenessFixture
{
    static final String TYPE = "sling:resourceType";

    static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    static final String DETAILS = "details";

    static final String START_DATE = "details/startDate";

    static final String NOTE = "details/note";

    static final String REASON = "details/why/reason";

    static final String DOCTORS_NOTE = "doctorsNote";

    /** Autocreated by a real repository from the node type, and by nothing in a mock one. */
    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String FORM_ITEM = "sch/FormItem";

    private CompletenessFixture()
    {
    }

    /**
     * Builds the schema and an empty submission answering it.
     *
     * @param context the mock context, JCR-backed for the references
     * @param hidden the names of the schema parts whose condition does not hold
     * @return the condition evaluator the evaluators under test are to be given
     * @throws Exception when a reference cannot be written
     */
    static ConditionEvaluator setUp(final SlingContext context, final Set<String> hidden) throws Exception
    {
        context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, FormRequirement.class, DocumentRequirement.class, ApprovalRequirement.class,
            Section.class, Question.class, Answer.class, Document.class, DocumentVersion.class, Submission.class);
        final ConditionEvaluator evaluator = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(evaluator.applies(Mockito.any(), Mockito.any()))
            .thenAnswer(call -> !hidden.contains(((Content) call.getArgument(0)).getName()));
        // The submission model asks for the evaluator through @OSGiService
        context.registerService(ConditionEvaluator.class, evaluator);

        context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request"));
        context.create().resource(VERSION_PATH, Map.of(TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0"));
        context.create().resource(VERSION_PATH + "/" + DETAILS, Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Request details"));
        context.create().resource(VERSION_PATH + "/" + START_DATE, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "Which day?", "minAnswers", 1L));
        context.create().resource(VERSION_PATH + "/" + NOTE, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "Anything to add?"));
        context.create().resource(VERSION_PATH + "/" + DETAILS + "/why", Map.of(
            TYPE, Section.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "title", "Why"));
        context.create().resource(VERSION_PATH + "/" + REASON, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "Why?", "minAnswers", 1L));
        context.create().resource(VERSION_PATH + "/" + DOCTORS_NOTE, Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Doctor's note"));

        context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend", "createdBy", "demo-requester"));
        reference(context, SUBMISSION_PATH, VERSION_PATH, "schemaVersion");
        return evaluator;
    }

    /**
     * Records an answer to one of the schema's questions.
     *
     * @param context the mock context
     * @param questionPath the question's path, relative to the schema version
     * @param values what was answered
     * @return the answer's resource
     * @throws Exception when the reference cannot be written
     */
    static Resource answer(final SlingContext context, final String questionPath, final String... values)
        throws Exception
    {
        final Resource answer = context.create().resource(SUBMISSION_PATH + "/" + questionPath.replace('/', '-'),
            Map.of(TYPE, Answer.RESOURCE_TYPE, "value", values));
        reference(context, answer.getPath(), VERSION_PATH + "/" + questionPath, "question");
        return answer;
    }

    /**
     * Files a document against one of the schema's requirements.
     *
     * @param context the mock context
     * @param requirement the requirement's name
     * @param attached whether a file has been uploaded into it
     * @return the document's resource
     * @throws Exception when the reference cannot be written
     */
    static Resource document(final SlingContext context, final String requirement, final boolean attached)
        throws Exception
    {
        final Resource document = context.create().resource(SUBMISSION_PATH + "/" + requirement
            + (attached ? "Attached" : "Empty"), Map.of(TYPE, Document.RESOURCE_TYPE));
        if (attached) {
            context.create().resource(document.getPath() + "/v1", Map.of(TYPE, DocumentVersion.RESOURCE_TYPE));
        }
        reference(context, document.getPath(), VERSION_PATH + "/" + requirement, "fulfills");
        return document;
    }

    /**
     * The submission's resource, read afresh, and writable already.
     *
     * @param context the mock context
     * @return the submission
     * @throws RepositoryException never, only declared by the mocked JCR API
     */
    static Resource submission(final SlingContext context) throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isCheckedOut()).thenReturn(true);
        return submission(context, node);
    }

    /**
     * The submission's resource, read afresh, reading as the given node: jcr-mock answers nothing about versioning,
     * so the node says whether the submission is checked in.
     *
     * @param context the mock context
     * @param node what the submission reads as when adapted to a node
     * @return the submission
     */
    static Resource submission(final SlingContext context, final Node node)
    {
        return new ResourceWrapper(Objects.requireNonNull(context.resourceResolver().getResource(SUBMISSION_PATH)))
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == Node.class ? type.cast(node) : super.adaptTo(type);
            }
        };
    }

    /**
     * The questions the submission's answers are for, sorted.
     *
     * @param context the mock context
     * @return the questions' paths, one per answer
     */
    static List<String> answeredQuestions(final SlingContext context)
    {
        return parts(context, Answer.RESOURCE_TYPE, "sub:Answer")
            .map(part -> Objects.requireNonNull(part.adaptTo(Answer.class)).getQuestion().getPath())
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * The requirements the submission's documents are filed against, sorted.
     *
     * @param context the mock context
     * @return the requirements' paths, one per document
     */
    static List<String> filedRequirements(final SlingContext context)
    {
        return parts(context, Document.RESOURCE_TYPE, "sub:Document")
            .map(part -> Objects.requireNonNull(part.adaptTo(Document.class)).getFulfills().getPath())
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * The submission's children of one kind. A part the code under test created carries only its primary type: a
     * real repository autocreates the resource type from the node type, and the mock one does not.
     */
    private static Stream<Resource> parts(final SlingContext context, final String resourceType,
        final String primaryType)
    {
        return StreamSupport.stream(Objects.requireNonNull(context.resourceResolver().getResource(SUBMISSION_PATH))
            .getChildren().spliterator(), false)
            .filter(child -> child.isResourceType(resourceType)
                || primaryType.equals(child.getValueMap().get("jcr:primaryType", String.class)));
    }

    static void reference(final SlingContext context, final String fromPath, final String toPath,
        final String property) throws Exception
    {
        final Node source =
            Objects.requireNonNull(context.resourceResolver().getResource(fromPath)).adaptTo(Node.class);
        final Node target =
            Objects.requireNonNull(context.resourceResolver().getResource(toPath)).adaptTo(Node.class);
        Objects.requireNonNull(source).setProperty(property, Objects.requireNonNull(target));
        context.resourceResolver().commit();
    }

    /**
     * Sets a reference on a component under test, the house idiom: DS metadata only exists in the packaged bundle.
     *
     * @param component the component
     * @param field the field to set
     * @param value what to set it to
     * @throws ReflectiveOperationException never, for the fields these tests name
     */
    static void inject(final Object component, final String field, final Object value)
        throws ReflectiveOperationException
    {
        final var reference = component.getClass().getDeclaredField(field);
        reference.setAccessible(true);
        reference.set(component, value);
    }
}
