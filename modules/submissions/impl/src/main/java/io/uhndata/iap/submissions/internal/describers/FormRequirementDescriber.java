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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.RequirementDescriber;

/**
 * What a form requirement adds to a form: its sections and questions that apply now, each question with the answer
 * it already has and, when a reading suggested one, where that came from.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = RequirementDescriber.class)
public class FormRequirementDescriber implements RequirementDescriber
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FormRequirementDescriber.class);

    private static final String ITEMS_KEY = "items";

    /** Whether a model fills the form in from an uploaded document. */
    private static final String EXTRACTED_KEY = "extracted";

    /** Where a question's suggested answer came from, present only when a reading found one. */
    private static final String PROVENANCE_KEY = "provenance";

    private static final String VALUE_KEY = "value";

    private static final String TEXT_KEY = "text";

    private static final String DATA_TYPE_KEY = "dataType";

    private static final String MIN_ANSWERS_KEY = "minAnswers";

    private static final String MAX_ANSWERS_KEY = "maxAnswers";

    private static final String OPTIONS_KEY = "options";

    private static final String MIN_VALUE_KEY = "minValue";

    private static final String MAX_VALUE_KEY = "maxValue";

    private static final String PATTERN_KEY = "pattern";

    private static final String PATTERN_MESSAGE_KEY = "patternMessage";

    /** The data type of a question that names none. */
    private static final String DEFAULT_DATA_TYPE = "text";

    private final ConditionEvaluator conditions;

    /**
     * Constructor.
     *
     * @param conditions decides which sections and questions apply now
     */
    @Activate
    public FormRequirementDescriber(@Reference final ConditionEvaluator conditions)
    {
        this.conditions = conditions;
    }

    @Override
    public boolean handles(final Requirement requirement)
    {
        return requirement instanceof FormRequirement;
    }

    @Override
    public void describe(final Requirement requirement, final Submission submission, final JsonObjectBuilder json)
    {
        final FormRequirement form = (FormRequirement) requirement;
        json.add(ITEMS_KEY, new ItemsWriter(submission).items(form.getChildren(), requirement.getName()));
        json.add(EXTRACTED_KEY, isExtracted(form));
    }

    /**
     * Whether a model fills this form in from an uploaded document: a classification always, any other form when
     * one of its questions, at any depth, says how to ask a model.
     *
     * @param form the form requirement
     * @return {@code true} when a reading answers something here
     */
    private static boolean isExtracted(final FormRequirement form)
    {
        return form instanceof ClassificationRequirement || hasExtractionPrompt(form.getChildren());
    }

    private static boolean hasExtractionPrompt(final List<FormItem> children)
    {
        return children.stream().anyMatch(child -> child instanceof Section
            ? hasExtractionPrompt(((Section) child).getChildren())
            : child instanceof Question && !Objects.toString(((Question) child).getExtractionPrompt(), "").isBlank());
    }

    /**
     * The items of one submission's form, read against the answers it holds.
     *
     * @since 0.1.0
     */
    private final class ItemsWriter
    {
        private final Submission submission;

        private final Map<String, List<String>> answers;

        /** The answers by their question's path, for what a reading suggested. */
        private final Map<String, Answer> answered;

        ItemsWriter(final Submission submission)
        {
            this.submission = submission;
            this.answers = submission.getAnswersByQuestion();
            this.answered = new HashMap<>();
            submission.getAnswers().forEach(answer -> Optional.ofNullable(answer.getQuestion())
                .ifPresent(question -> this.answered.putIfAbsent(question.getPath(), answer)));
        }

        /**
         * The items of a form or a section, in the order the schema puts them, skipping whatever does not apply.
         *
         * @param children the form items to describe
         * @param prefix the path of their container, relative to the schema version, such as {@code details}
         * @return the items' JSON
         */
        JsonArrayBuilder items(final List<FormItem> children, final String prefix)
        {
            final JsonArrayBuilder items = Json.createArrayBuilder();
            children.stream()
                .filter(child -> FormRequirementDescriber.this.conditions.applies(child, this.submission))
                .map(child -> item(child, prefix + "/" + child.getName()))
                .flatMap(Optional::stream)
                .forEach(items::add);
            return items;
        }

        /**
         * One section or question.
         *
         * @param child the form item
         * @param path its path relative to the schema version
         * @return its JSON; empty for a kind of form item that is neither a section nor a question
         */
        private Optional<JsonObjectBuilder> item(final FormItem child, final String path)
        {
            if (child instanceof Section) {
                return Optional.of(section((Section) child, path));
            }
            if (child instanceof Question) {
                return Optional.of(question((Question) child, path));
            }
            LOGGER.warn("{} is a {}, which a form cannot show", child.getPath(), child.getType());
            ErrorLogger.logProblem("A form item of a kind a form cannot show",
                ErrorContext.of(FormRequirementDescriber.class, "describe")
                    .about(this.submission.getPath())
                    .with("item", child.getPath())
                    .with("type", child.getType()));
            return Optional.empty();
        }

        private JsonObjectBuilder section(final Section section, final String path)
        {
            return Json.createObjectBuilder()
                .add(FormKeys.NAME_KEY, section.getName())
                .add(FormKeys.TYPE_KEY, section.getType())
                .add(FormKeys.LABEL_KEY, Objects.toString(section.getTitle(), ""))
                .add(FormKeys.DESCRIPTION_KEY, Objects.toString(section.getDescription(), ""))
                .add(ITEMS_KEY, items(section.getChildren(), path));
        }

        /**
         * One question, with the answer it already has.
         *
         * <p>It carries its {@code path} relative to the schema version, which is how the save workflow names a
         * question. An editor posts that path back as it was given.</p>
         *
         * @param question the question to describe
         * @param path its path relative to the schema version
         * @return the question's JSON
         */
        private JsonObjectBuilder question(final Question question, final String path)
        {
            final JsonArrayBuilder value = Json.createArrayBuilder();
            this.answers.getOrDefault(question.getPath(), List.of()).forEach(value::add);
            final JsonObjectBuilder json = Json.createObjectBuilder()
                .add(FormKeys.NAME_KEY, question.getName())
                .add(FormKeys.TYPE_KEY, question.getType())
                .add(FormKeys.PATH_KEY, path)
                .add(TEXT_KEY, Objects.toString(question.getText(), ""))
                .add(FormKeys.DESCRIPTION_KEY, Objects.toString(question.getDescription(), ""))
                .add(DATA_TYPE_KEY, Objects.toString(question.getDataType(), DEFAULT_DATA_TYPE))
                // The answer-count pair as read from the question; the editor derives required and multiple from it
                .add(MIN_ANSWERS_KEY, question.getMinAnswers())
                .add(MAX_ANSWERS_KEY, question.getMaxAnswers())
                .add(OPTIONS_KEY, options(question))
                .add(VALUE_KEY, value);
            addStatedConstraints(json, question);
            ProvenanceProjection.describe(this.answered.get(question.getPath()))
                .ifPresent(provenance -> json.add(PROVENANCE_KEY, provenance));
            return json;
        }
    }

    /**
     * The answers a question offers, as the editor shows them. They are stated even when empty, for a question
     * answered freely.
     *
     * @param question the question
     * @return its options, in stored order; empty if it offers none
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
            .ifPresent(bound -> json.add(MIN_VALUE_KEY, bound));
        Optional.ofNullable(question.getMaxValue()).filter(Double::isFinite)
            .ifPresent(bound -> json.add(MAX_VALUE_KEY, bound));
        Optional.ofNullable(question.getPattern()).ifPresent(pattern -> json.add(PATTERN_KEY, pattern));
        Optional.ofNullable(question.getPatternMessage())
            .ifPresent(message -> json.add(PATTERN_MESSAGE_KEY, message));
    }
}
