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
package io.uhndata.iap.schemas.editing.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.conditions.api.Aggregator;
import io.uhndata.iap.conditions.api.Operator;
import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.Question;

/**
 * What must hold before a draft may be published: everything a submitter or a condition will lean on once the
 * version can no longer change. Problems are gathered rather than stopping at the first, so they can all be
 * fixed in one go.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class PublishCheck
{
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final Pattern UUID_FORMAT =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

    private final Resource version;

    private final List<String> problems = new ArrayList<>();

    private PublishCheck(final Resource version)
    {
        this.version = version;
    }

    /**
     * Everything that stops a draft from being published.
     *
     * @param version the draft
     * @return the problems found, one sentence each, empty when the draft may be published
     */
    @NotNull
    static List<String> problems(@NotNull final Resource version)
    {
        final PublishCheck check = new PublishCheck(version);
        check.visit(version);
        return check.problems;
    }

    private void visit(final Resource resource)
    {
        final String type = resource.getValueMap().get(PRIMARY_TYPE, "");
        if ("sch:Question".equals(type)) {
            checkQuestion(resource);
        } else if ("cond:SingleCondition".equals(type)) {
            checkComparator(resource);
        } else if ("cond:ConditionOperand".equals(type)) {
            checkOperand(resource);
        }
        resource.getChildren().forEach(this::visit);
    }

    private void checkQuestion(final Resource resource)
    {
        final Question question = Objects.requireNonNull(resource.adaptTo(Question.class),
            "A sch:Question resource failed to adapt to its model");
        final String where = where(resource);
        checkBounds(question, where);
        checkPattern(question, where);
        checkOptions(question, where);
    }

    private void checkBounds(final Question question, final String where)
    {
        if (question.getMaxAnswers() > 0 && question.getMinAnswers() > question.getMaxAnswers()) {
            this.problems.add(where + " asks for at least " + question.getMinAnswers() + " answers but allows at most "
                + question.getMaxAnswers());
        }
        final Double min = question.getMinValue();
        final Double max = question.getMaxValue();
        if (min != null && max != null && min > max) {
            this.problems.add(where + " has a smallest accepted value above its largest");
        }
    }

    private void checkPattern(final Question question, final String where)
    {
        final String pattern = question.getPattern();
        if (pattern != null) {
            try {
                Pattern.compile(pattern);
            } catch (final PatternSyntaxException e) {
                this.problems.add(where + " has a pattern that is not a valid regular expression");
            }
        }
    }

    private void checkOptions(final Question question, final String where)
    {
        final Set<String> values = new HashSet<>();
        for (final AnswerOption option : question.getOptions()) {
            final String value = option.getValue();
            if (value == null || value.isBlank()) {
                this.problems.add(where + " has an option without a value");
            } else if (!values.add(value)) {
                this.problems.add(where + " offers the value \"" + value + "\" more than once");
            }
        }
    }

    private void checkComparator(final Resource condition)
    {
        final String comparator = condition.getValueMap().get("comparator", "");
        try {
            Operator.parse(comparator);
        } catch (final IllegalArgumentException e) {
            this.problems.add(where(conditioned(condition)) + " has a condition comparing with \"" + comparator
                + "\", which is not a known comparison");
        }
    }

    private void checkOperand(final Resource operand)
    {
        final ValueMap properties = operand.getValueMap();
        final String aggregate = properties.get("aggregate", String.class);
        if (aggregate != null) {
            try {
                Aggregator.parse(aggregate);
            } catch (final IllegalArgumentException e) {
                this.problems.add(where(conditioned(operand)) + " has a condition aggregating with \"" + aggregate
                    + "\", which is not a known aggregate");
            }
        }
        if (!"answer".equals(properties.get("source", "literal"))) {
            return;
        }
        final String[] reference = properties.get("value", new String[0]);
        if (reference.length == 0 || !isQuestionOfThisVersion(reference[0])) {
            this.problems.add(where(conditioned(operand)) + " has a condition on a question that is not in "
                + "this version");
        }
    }

    private boolean isQuestionOfThisVersion(final String reference)
    {
        if (!UUID_FORMAT.matcher(reference).matches()) {
            final Resource question = this.version.getChild(reference);
            return question != null && "sch:Question".equals(question.getValueMap().get(PRIMARY_TYPE, ""));
        }
        final Session session = Objects.requireNonNull(this.version.getResourceResolver().adaptTo(Session.class),
            "Schemas are stored in a JCR repository");
        try {
            final Node question = session.getNodeByIdentifier(reference);
            return question.getPath().startsWith(this.version.getPath() + "/") && question.isNodeType("sch:Question");
        } catch (final RepositoryException e) {
            // Not found, which is exactly the problem being looked for
            return false;
        }
    }

    // The schema part a condition belongs to: the nearest ancestor that is not part of the condition itself
    private Resource conditioned(final Resource conditionPart)
    {
        Resource current = conditionPart;
        Resource parent = current.getParent();
        while (parent != null && current.getValueMap().get(PRIMARY_TYPE, "").startsWith("cond:")) {
            current = parent;
            parent = current.getParent();
        }
        return current;
    }

    // How to call a schema part in a message: by what the submitter reads, or by its path when that is blank
    private String where(final Resource resource)
    {
        for (final String property : new String[] { "text", "label", "title" }) {
            final String name = resource.getValueMap().get(property, "");
            if (!name.isBlank()) {
                return "\"" + name + "\"";
            }
        }
        return resource.getPath().substring(this.version.getPath().length() + 1);
    }
}
