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
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.documents.generation.spi.TemplateContextProvider;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Submission;

/**
 * What a template knows about the submission it is filled in for, as {@code submission}: its {@code title}, when it
 * was {@code created}, and its {@code answers}.
 *
 * <p>The answers follow the schema's own tree, by node name, so the question at {@code details/day} is
 * {@code {{ submission.answers.details.day }}}. Question names only have to be unique among their siblings, and two
 * forms asking for a "name" is ordinary, so anything flatter would be ambiguous. Every question is there, answered or
 * not, so that an optional question left blank prints nothing rather than failing the template: a single answer is
 * its text, empty when unanswered, and a question taking several is a list. A question that does not currently
 * apply, by its own condition or one around it, counts as unanswered, whatever was stored for it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = TemplateContextProvider.class)
public class SubmissionTemplateValues implements TemplateContextProvider
{
    /** The name templates use for these values. */
    static final String NAME = "submission";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public Object getValue(final Resource target)
    {
        // Checked by type: a model adapts from any resource at all
        final Submission submission = target.isResourceType(Submission.RESOURCE_TYPE)
            ? target.adaptTo(Submission.class) : null;
        if (submission == null) {
            return null;
        }

        // Put together what the template sees
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", submission.getTitle());
        values.put("created", submission.getCreated());
        final Resource version = Objects.requireNonNull(
            target.getResourceResolver().getResource(submission.getSchemaVersion().getPath()),
            "A submission's schema version is readable wherever the submission is");
        values.put("answers", answers(version, submission, submission.getAnswersByQuestion(), true));
        return values;
    }

    /**
     * The questions under a part of the schema, with their answers, nested as the schema nests them.
     *
     * @param part a schema version, or a requirement or section in one
     * @param submission the submission, whose answers decide what applies
     * @param given the submission's answers, by question path
     * @param asked whether {@code part} applies; when it does not, nothing under it does either
     * @return the questions and the parts holding questions under this one, by node name; possibly empty
     */
    private static Map<String, Object> answers(final Resource part, final Submission submission,
        final Map<String, List<String>> given, final boolean asked)
    {
        final Map<String, Object> answers = new LinkedHashMap<>();
        for (final Resource child : part.getChildren()) {
            final Question question = child.isResourceType(Question.RESOURCE_TYPE) ? child.adaptTo(Question.class)
                : null;
            if (question != null) {
                // One that no longer applies keeps its stored answer, which must not reach the document. It is
                // blank rather than missing, so a template mentioning it still fills in
                final boolean applies = asked && submission.isApplicable(question);
                answers.put(child.getName(),
                    answer(question, applies ? given.getOrDefault(child.getPath(), List.of()) : List.of()));
                continue;
            }
            // A part with no questions under it, such as a condition, is not something a template asks about
            final Map<String, Object> nested = answers(child, submission, given, asked && applies(child, submission));
            if (!nested.isEmpty()) {
                answers.put(child.getName(), nested);
            }
        }
        return answers;
    }

    /**
     * Whether a section or requirement applies to the submission. Anything else has no condition of its own.
     *
     * @param part a part of the schema
     * @param submission the submission
     * @return {@code false} only for a section or requirement whose condition does not hold
     */
    private static boolean applies(final Resource part, final Submission submission)
    {
        // Checked by type: a model adapts from any resource at all
        final Conditionable conditionable = part.isResourceType(Section.RESOURCE_TYPE) ? part.adaptTo(Section.class)
            : part.isResourceType(Requirement.RESOURCE_TYPE) ? part.adaptTo(Requirement.class) : null;
        return conditionable == null || submission.isApplicable(conditionable);
    }

    private static Object answer(final Question question, final List<String> values)
    {
        if (question.isMultiple()) {
            return values;
        }
        return values.isEmpty() ? "" : values.get(0);
    }
}
