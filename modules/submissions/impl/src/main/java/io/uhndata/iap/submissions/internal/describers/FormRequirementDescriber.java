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
package io.uhndata.iap.submissions.internal.describers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.RequirementDescriber;

/**
 * What a set of questions adds to a form: its sections and questions that currently apply, each question with the
 * answer it already has.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = RequirementDescriber.class)
public class FormRequirementDescriber implements RequirementDescriber
{
    private static final String ITEMS_KEY = "items";

    private static final String VALUE_KEY = "value";

    @Reference
    private ConditionEvaluator conditions;

    @Override
    public boolean handles(final Requirement requirement)
    {
        return requirement instanceof FormRequirement;
    }

    @Override
    public void describe(final Requirement requirement, final Submission submission, final JsonObjectBuilder json)
    {
        json.add(ITEMS_KEY, items(((FormRequirement) requirement).getChildren(), requirement.getName(), submission,
            submission.getAnswersByQuestion()));
    }

    /**
     * The items of a form or a section, in the order the schema puts them, skipping whatever does not apply.
     *
     * @param children the form items to describe
     * @param prefix the path of their container, relative to the schema version
     * @param submission the submission they are being resolved against
     * @param answers the submission's answers, by question path
     * @return the items' JSON
     */
    private JsonArrayBuilder items(final List<FormItem> children, final String prefix, final Submission submission,
        final Map<String, List<String>> answers)
    {
        final JsonArrayBuilder items = Json.createArrayBuilder();
        children.stream()
            .filter(child -> this.conditions.applies(child, submission))
            .forEach(child -> {
                final String path = prefix + "/" + child.getName();
                if (child instanceof Section) {
                    final Section section = (Section) child;
                    items.add(Json.createObjectBuilder()
                        .add(FormKeys.NAME_KEY, section.getName())
                        .add(FormKeys.TYPE_KEY, section.getType())
                        .add(FormKeys.LABEL_KEY, Objects.toString(section.getTitle(), ""))
                        .add(FormKeys.DESCRIPTION_KEY, Objects.toString(section.getDescription(), ""))
                        .add(ITEMS_KEY, items(section.getChildren(), path, submission, answers)));
                } else if (child instanceof Question) {
                    items.add(question((Question) child, path, answers));
                }
            });
        return items;
    }

    /**
     * One question, with the answer it already has.
     *
     * <p>It carries its own {@code path}, relative to the schema version, which is what the save workflow
     * asks for. An editor posts back what it was given instead of working out how to address a question.</p>
     *
     * @param question the question to describe
     * @param path its path relative to the schema version
     * @param answers the submission's answers, by question path
     * @return the question's JSON
     */
    private static JsonObjectBuilder question(final Question question, final String path,
        final Map<String, List<String>> answers)
    {
        final JsonArrayBuilder value = Json.createArrayBuilder();
        answers.getOrDefault(question.getPath(), List.of()).forEach(value::add);
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add(FormKeys.NAME_KEY, question.getName())
            .add(FormKeys.TYPE_KEY, question.getType())
            .add(FormKeys.PATH_KEY, path)
            .add("text", Objects.toString(question.getText(), ""))
            .add(FormKeys.DESCRIPTION_KEY, Objects.toString(question.getDescription(), ""))
            .add("dataType", Objects.toString(question.getDataType(), "text"))
            // The answer-count pair itself rather than flags derived from it, read the way it is stored
            .add("minAnswers", question.getMinAnswers())
            .add("maxAnswers", question.getMaxAnswers())
            .add("options", options(question))
            .add(VALUE_KEY, value);
        addStatedConstraints(json, question);
        return json;
    }

    /**
     * The answers a question offers, as the editor shows them. Stated even when empty, so that "answered freely" is
     * something the form says.
     *
     * @param question the question
     * @return its options, in the order the schema declares them
     */
    private static JsonArrayBuilder options(final Question question)
    {
        final JsonArrayBuilder options = Json.createArrayBuilder();
        question.getOptions().forEach(option -> options.add(Json.createObjectBuilder()
            .add(VALUE_KEY, option.getValue())
            .add(FormKeys.LABEL_KEY, option.getLabel())));
        return options;
    }

    /**
     * Adds the value constraints a question states, and only those.
     *
     * <p>A bound that is not finite has no JSON form, so it is left out rather than failing the whole form.</p>
     *
     * @param json the question's JSON
     * @param question the question
     */
    private static void addStatedConstraints(final JsonObjectBuilder json, final Question question)
    {
        Optional.ofNullable(question.getMinValue()).filter(Double::isFinite)
            .ifPresent(bound -> json.add("minValue", bound));
        Optional.ofNullable(question.getMaxValue()).filter(Double::isFinite)
            .ifPresent(bound -> json.add("maxValue", bound));
        Optional.ofNullable(question.getPattern()).ifPresent(pattern -> json.add("pattern", pattern));
        Optional.ofNullable(question.getPatternMessage()).ifPresent(message -> json.add("patternMessage", message));
    }
}
