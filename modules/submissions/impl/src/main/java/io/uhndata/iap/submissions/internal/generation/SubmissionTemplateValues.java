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

import io.uhndata.iap.documents.generation.spi.TemplateContextProvider;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Submission;

/**
 * What a template knows about the submission it is filled in for, as {@code submission}: its {@code title}, when it
 * was {@code created}, and its {@code answers}.
 *
 * <p>The answers follow the schema's own tree, by node name, so the question at {@code details/day} is
 * {@code {{ submission.answers.details.day }}}. Question names only have to be unique among their siblings, and two
 * forms asking for a "name" is ordinary, so anything flatter would be ambiguous. Every question is there, answered or
 * not, so that an optional question left blank prints nothing rather than failing the template: a single answer is
 * its text, empty when unanswered, and a question taking several is a list.</p>
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
        values.put("answers", answers(version, submission.getAnswersByQuestion()));
        return values;
    }

    /**
     * The questions under a part of the schema, with their answers, nested as the schema nests them.
     *
     * @param part a schema version, or a requirement or section in one
     * @param given the submission's answers, by question path
     * @return the questions and the parts holding questions under this one, by node name; possibly empty
     */
    private static Map<String, Object> answers(final Resource part, final Map<String, List<String>> given)
    {
        final Map<String, Object> answers = new LinkedHashMap<>();
        for (final Resource child : part.getChildren()) {
            final Question question = child.isResourceType(Question.RESOURCE_TYPE) ? child.adaptTo(Question.class)
                : null;
            if (question != null) {
                answers.put(child.getName(), answer(question, given.getOrDefault(child.getPath(), List.of())));
                continue;
            }
            // A part with no questions under it, such as a condition, is not something a template asks about
            final Map<String, Object> nested = answers(child, given);
            if (!nested.isEmpty()) {
                answers.put(child.getName(), nested);
            }
        }
        return answers;
    }

    private static Object answer(final Question question, final List<String> values)
    {
        if (question.isMultiple()) {
            return values;
        }
        return values.isEmpty() ? "" : values.get(0);
    }
}
