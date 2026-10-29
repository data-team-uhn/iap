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
package io.uhndata.iap.demos.timeoff.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Time off requests as these tests need them: the questions the demo's rules read, and requests answering them.
 * The context must be JCR-backed, since a request names its schema version, and an answer its question, by
 * {@code jcr:uuid}.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TimeOffRequests
{
    /** The path of the schema version every request answers. */
    static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String TYPE = "sling:resourceType";

    private static final String VALUE = "value";

    private final SlingContext context;

    private TimeOffRequests(final SlingContext context)
    {
        this.context = context;
    }

    /**
     * Creates the questions the demo's rules read.
     *
     * @param context the test's JCR-backed Sling context
     * @return the way to create requests answering them
     */
    static TimeOffRequests installIn(final SlingContext context)
    {
        context.create().resource(VERSION_PATH, TYPE, SchemaVersion.RESOURCE_TYPE);
        for (final String question : List.of(TimeOffQuestions.DURATION_QUESTION, TimeOffQuestions.START_DATE_QUESTION,
            TimeOffQuestions.END_DATE_QUESTION)) {
            context.create().resource(VERSION_PATH + "/" + question, TYPE, Question.RESOURCE_TYPE);
        }
        return new TimeOffRequests(context);
    }

    /**
     * Creates a request answering the schema version.
     *
     * @param path the absolute path of the new {@code sub:Submission}
     * @param properties further properties by name, such as {@code tags} or {@code budgetRemainingDays}
     * @return the new submission
     */
    Resource request(final String path, final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put(TYPE, Submission.RESOURCE_TYPE);
        all.put("schemaVersion", this.identifierOf(VERSION_PATH));
        return this.context.create().resource(path, all);
    }

    /**
     * Answers one question of a request, replacing any earlier answer to it.
     *
     * @param request the submission to answer
     * @param question the question's path within the schema version, such as {@code details/startDate}
     * @param value the answer's single value as stored, such as {@code 2026-09-16} for a date or {@code half-day}
     *     for the duration
     */
    void answer(final Resource request, final String question, final String value)
    {
        final String path = request.getPath() + "/" + question.substring(question.lastIndexOf('/') + 1);
        final Resource existing = this.resolver().getResource(path);
        if (existing == null) {
            this.context.create().resource(path, Map.of(TYPE, Answer.RESOURCE_TYPE,
                "question", this.identifierOf(VERSION_PATH + "/" + question), VALUE, new String[] {value}));
            return;
        }
        Objects.requireNonNull(existing.adaptTo(ModifiableValueMap.class)).put(VALUE, new String[] {value});
        try {
            this.resolver().commit();
        } catch (final PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The tags a resource carries now.
     *
     * @param path the absolute path of an existing resource
     * @return the tag names in its {@code tags} property; empty if it has none
     */
    Set<String> tagsOf(final String path)
    {
        return Set.of(Objects.requireNonNull(this.resolver().getResource(path)).getValueMap()
            .get("tags", new String[0]));
    }

    private String identifierOf(final String path)
    {
        try {
            return Objects.requireNonNull(Objects.requireNonNull(this.resolver().getResource(path))
                .adaptTo(Node.class)).getIdentifier();
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResourceResolver resolver()
    {
        return this.context.resourceResolver();
    }
}
