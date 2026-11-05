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
package io.uhndata.iap.demo.review;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * PRETOTYPE. What a reviewer writes on a submission, as one event: an issue for the study team, in the reviewer's
 * open review, tagged with its {@code severity}; a message to the other reviewers, in the submission's discussion;
 * or a note to self, in the reviewer's own notes. The payload's {@code kind} says which, {@code text} what it says,
 * {@code subjects} the answers or documents it is about, by UUID, and {@code source}, {@code quote},
 * {@code prefix}, {@code suffix} and {@code page} the passage it points at, when it points at one. With
 * {@code replyTo} naming a comment, it is a reply to it instead; with {@code edit} naming one of the writer's own
 * comments, it changes that comment's text and severity; with {@code remove}, it removes it. An issue made from an
 * AI pre-review comment names it as {@code from}; with {@code dismiss} naming one, the reviewer sets it aside, and
 * it is recorded on the AI comment, in {@code dismissedBy}, since another reviewer may still want it. An issue is
 * filed under the risk {@code topic} names, by path, when it names one; editing it with an empty one unfiles it.
 *
 * <p>The notes are kept apart from the review and the discussion, in an {@code irv:Notes} node per reviewer. They
 * are not hidden from the other reviewers by an access rule yet: the pages only show a reviewer their own.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ReviewCommentHandler implements ServiceTaskHandler
{
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String TEXT_PROPERTY = "text";

    private static final String AUTHOR_PROPERTY = "author";

    private static final String DISMISSED_PROPERTY = "dismissedBy";

    private static final String TOPIC_PROPERTY = "topic";

    @Override
    public String getName()
    {
        return "reviewComment";
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource submission = ExecutionHost.of(context);
        final String actor = context.getActor();
        final String dismissed = Payloads.text(context.getEvent(), "dismiss");
        if (dismissed != null) {
            dismiss(within(submission, dismissed), actor);
            return;
        }
        final String removed = Payloads.text(context.getEvent(), "remove");
        if (removed != null) {
            submission.getResourceResolver().delete(ownComment(submission, removed, actor));
            return;
        }
        final String text = Payloads.requireText(context.getEvent(), TEXT_PROPERTY, "Say something");
        final String edited = Payloads.text(context.getEvent(), "edit");
        final String replyTo = Payloads.text(context.getEvent(), "replyTo");
        if (edited != null) {
            edit(context, ownComment(submission, edited, actor), text);
        } else if (replyTo != null) {
            submission.getResourceResolver().create(within(submission, replyTo), name("reply"),
                Map.of(PRIMARY_TYPE, "sub:Reply", TEXT_PROPERTY, text, AUTHOR_PROPERTY, actor));
        } else {
            write(context, submission, actor, text);
        }
    }

    private static void dismiss(final Resource comment, final String actor)
    {
        final ModifiableValueMap values = Objects.requireNonNull(comment.adaptTo(ModifiableValueMap.class),
            "Writable");
        final List<String> by = new ArrayList<>(Arrays.asList(values.get(DISMISSED_PROPERTY, new String[0])));
        if (!by.contains(actor)) {
            by.add(actor);
            values.put(DISMISSED_PROPERTY, by.toArray(String[]::new));
        }
    }

    private static void edit(final WorkflowTaskContext context, final Resource comment, final String text)
        throws PersistenceException, InvalidPayloadException
    {
        Objects.requireNonNull(comment.adaptTo(ModifiableValueMap.class), "Writable").put(TEXT_PROPERTY, text);
        final String severity = Payloads.text(context.getEvent(), "severity");
        if (severity != null) {
            Objects.requireNonNull(comment.adaptTo(Taggable.class), "Taggable").tagReplacing(severity, false);
        }
        if (context.getEvent().getPayload().containsKey(TOPIC_PROPERTY)) {
            final String topic = Payloads.text(context.getEvent(), TOPIC_PROPERTY);
            final ModifiableValueMap values = Objects.requireNonNull(comment.adaptTo(ModifiableValueMap.class),
                "Writable");
            if (topic == null) {
                values.remove(TOPIC_PROPERTY);
            } else {
                values.put(TOPIC_PROPERTY, topic(comment, topic));
            }
        }
    }

    // A risk of the schema, by path
    private static String topic(final Resource near, final String path) throws InvalidPayloadException
    {
        final Resource topic = near.getResourceResolver().getResource(path);
        if (topic == null || !"sch:Topic".equals(topic.getValueMap().get(PRIMARY_TYPE, String.class))) {
            throw new InvalidPayloadException("There is no risk " + path);
        }
        return topic.getPath();
    }

    private static void write(final WorkflowTaskContext context, final Resource submission, final String actor,
        final String text) throws WorkflowException, PersistenceException
    {
        final String kind = Payloads.requireText(context.getEvent(), "kind", "Say what kind of comment this is");
        final Resource holder = holder(submission, kind, actor);
        final Map<String, Object> properties = new HashMap<>(Map.of(PRIMARY_TYPE, "sub:Comment",
            TEXT_PROPERTY, text, AUTHOR_PROPERTY, actor));
        final List<String> subjects = values(context, "subjects");
        if (!subjects.isEmpty()) {
            properties.put("subjects", subjects.toArray(String[]::new));
        }
        final String from = Payloads.text(context.getEvent(), "from");
        if ("issue".equals(kind) && from != null) {
            properties.put("from", within(submission, from).getPath());
        }
        final String topic = Payloads.text(context.getEvent(), TOPIC_PROPERTY);
        if ("issue".equals(kind) && topic != null) {
            properties.put(TOPIC_PROPERTY, topic(submission, topic));
        }
        final Resource comment = submission.getResourceResolver().create(holder, name("comment"), properties);
        final String severity = Payloads.text(context.getEvent(), "severity");
        if ("issue".equals(kind) && severity != null) {
            Objects.requireNonNull(comment.adaptTo(Taggable.class), "Taggable").tag(severity, false);
        }
        passage(context, comment);
    }

    // The passage a comment points at, when it points at one
    private static void passage(final WorkflowTaskContext context, final Resource comment)
        throws PersistenceException
    {
        final String quote = Payloads.text(context.getEvent(), "quote");
        if (quote == null) {
            return;
        }
        final Map<String, Object> passage = new HashMap<>(Map.of(PRIMARY_TYPE, "sub:Context", "quote", quote));
        for (final String field : List.of("source", "prefix", "suffix")) {
            final String value = Payloads.text(context.getEvent(), field);
            if (value != null) {
                passage.put(field, value);
            }
        }
        final String page = Payloads.text(context.getEvent(), "page");
        if (page != null) {
            passage.put("page", Long.valueOf(page));
        }
        comment.getResourceResolver().create(comment, "context", passage);
    }

    // Where a comment of this kind goes, made the first time one is written
    private static Resource holder(final Resource submission, final String kind, final String actor)
        throws PersistenceException, WorkflowException
    {
        final String name;
        final Map<String, Object> properties = new HashMap<>();
        switch (kind) {
            case "issue" -> {
                for (final Resource child : submission.getChildren()) {
                    final var values = child.getValueMap();
                    if ("sub/Review".equals(child.getResourceType()) && actor.equals(values.get("reviewer"))
                        && Arrays.asList(values.get("tags", new String[0])).contains("in-progress")) {
                        return child;
                    }
                }
                name = "review-" + actor;
                properties.putAll(Map.of(PRIMARY_TYPE, "sub:Review", "reviewer", actor, "tags",
                    new String[] { "in-progress" }));
            }
            case "discussion" -> {
                name = "discussion";
                properties.put(PRIMARY_TYPE, "sub:Discussion");
            }
            case "note" -> {
                name = "notes-" + actor;
                properties.putAll(Map.of(PRIMARY_TYPE, "irv:Notes", "owner", actor));
            }
            case null, default -> throw new InvalidPayloadException(
                "A comment is an issue, a discussion or a note, not " + kind);
        }
        final Resource existing = submission.getChild(name);
        return existing != null ? existing : submission.getResourceResolver().create(submission, name, properties);
    }

    private static Resource within(final Resource submission, final String path) throws WorkflowException
    {
        final Resource found = submission.getResourceResolver().getResource(path);
        if (found == null || !path.startsWith(submission.getPath() + "/")) {
            throw new InvalidPayloadException("There is no comment " + path + " on this submission");
        }
        return found;
    }

    private static Resource ownComment(final Resource submission, final String path, final String actor)
        throws WorkflowException
    {
        final Resource comment = within(submission, path);
        if (!actor.equals(comment.getValueMap().get(AUTHOR_PROPERTY, String.class))) {
            throw new NotAuthorizedException("Only its author can change a comment");
        }
        return comment;
    }

    private static List<String> values(final WorkflowTaskContext context, final String name)
    {
        final Object value = context.getEvent().get(name);
        if (value instanceof String[] many) {
            return Arrays.stream(many).filter(one -> !one.isBlank()).toList();
        }
        return value instanceof String one && !one.isBlank() ? List.of(one) : List.of();
    }

    private static String name(final String prefix)
    {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
