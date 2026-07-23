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

import { type ReactNode, useEffect, useState } from "react";

import { Alert, Box, Divider, Link, Paper, Stack, Typography } from "@mui/material";
import { Link as RouterLink, useLocation } from "react-router";

import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";

import StatusChip from "./StatusChip";
import { schemaLabel } from "./submissionGrid";

// A serialized JCR node: its properties, plus its children as nested objects.
type JsonNode = Record<string, unknown>;

function isNode(value: unknown): value is JsonNode {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

// The children of a serialized node having the given resource type, in their storage order.
function childrenOfType(node: JsonNode, resourceType: string): JsonNode[] {
  return Object.values(node)
    .filter(isNode)
    .filter(child => child["sling:resourceType"] === resourceType);
}

function formatValue(value: unknown): string {
  if (Array.isArray(value)) {
    return value.map(entry => formatValue(entry)).join(", ");
  }
  if (typeof value === "boolean") {
    return value ? "Yes" : "No";
  }
  return value == undefined ? "" : String(value);
}

function formatDate(value: unknown): string {
  return value ? new Date(String(value)).toLocaleString() : "";
}

// One question with its answer (or a placeholder when unanswered).
function QuestionRow({ question, answers }: { question: JsonNode; answers: JsonNode[] }) {
  const answer = answers.find(candidate =>
    isNode(candidate.question) && candidate.question["@path"] === question["@path"]);
  const value = answer && formatValue(answer.value);
  return (
    <Box>
      <Typography variant="subtitle2">{String(question.text ?? question["@name"])}</Typography>
      {value
        ? <Typography>{value}</Typography>
        : <Typography color="text.secondary">Not answered yet</Typography>}
    </Box>
  );
}

// The items of a form or section: questions, and nested sections with their own headings.
function FormItems({ container, answers, level }: { container: JsonNode; answers: JsonNode[]; level: number }) {
  const items = Object.values(container).filter(isNode);
  return (
    <Stack spacing={2}>
      {items.map((item, index) => {
        if (item["sling:resourceType"] === "sch/Question") {
          return <QuestionRow key={"item-" + index} question={item} answers={answers} />;
        }
        if (item["sling:resourceType"] === "sch/Section") {
          return (
            <Box key={"item-" + index}>
              <Typography variant={level === 0 ? "subtitle1" : "subtitle2"} sx={{ fontWeight: "bold", mb: 1 }}>
                {String(item.title ?? item["@name"])}
              </Typography>
              {item.description ? <Typography color="text.secondary">{String(item.description)}</Typography> : null}
              <FormItems container={item} answers={answers} level={level + 1} />
            </Box>
          );
        }
        return null;
      })}
    </Stack>
  );
}

// One titled block of the view, rendered as an outlined surface.
function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Typography variant="h6" gutterBottom>{title}</Typography>
      {subtitle ? <Typography color="text.secondary" gutterBottom>{subtitle}</Typography> : null}
      {children}
    </Paper>
  );
}

// The documents attached to the submission, with download links for their files.
function Documents({ documents }: { documents: JsonNode[] }) {
  return (
    <Stack spacing={2}>
      {documents.map((document, index) => {
        const requirement = isNode(document.fulfills) ? document.fulfills : undefined;
        const files = Object.entries(document)
          .filter(([, value]) => isNode(value) && value["jcr:primaryType"] === "nt:file");
        return (
          <Box key={"document-" + index}>
            <Typography variant="subtitle2">
              {String(document.title ?? document["@name"])}
              {requirement ? ` — fulfills "${String(requirement.label)}"` : ""}
            </Typography>
            {document.description ? <Typography color="text.secondary">{String(document.description)}</Typography> : null}
            <Stack>
              {files.map(([name]) =>
                <Link key={name} href={`${String(document["@path"])}/${name}`} download>{name}</Link>)}
            </Stack>
          </Box>
        );
      })}
    </Stack>
  );
}

// The reviews added to the submission, each with its threaded comments.
function Reviews({ reviews }: { reviews: JsonNode[] }) {
  return (
    <Stack spacing={2} divider={<Divider />}>
      {reviews.map((review, index) => {
        const requirement = isNode(review.requirement) ? review.requirement : undefined;
        const comments = childrenOfType(review, "sub/ReviewComment");
        return (
          <Box key={"review-" + index}>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Typography variant="subtitle2">{String(review.reviewer)}</Typography>
              {requirement ? <Typography color="text.secondary">on {String(requirement.label)}</Typography> : null}
              <StatusChip value={review.status} />
            </Stack>
            <Stack spacing={1} sx={{ mt: 1 }}>
              {comments.map((comment, commentIndex) => (
                <Box key={"comment-" + commentIndex} sx={{ pl: 2, borderInlineStart: 2, borderColor: "divider" }}>
                  <Typography>
                    <b>{String(comment.author)}</b>: {String(comment.text)}
                    {comment.resolved ? " ✓" : ""}
                  </Typography>
                  {childrenOfType(comment, "sub/Reply").map((reply, replyIndex) => (
                    <Typography key={"reply-" + replyIndex} sx={{ pl: 2 }}>
                      <b>{String(reply.author)}</b>: {String(reply.text)}
                    </Typography>
                  ))}
                </Box>
              ))}
            </Stack>
          </Box>
        );
      })}
    </Stack>
  );
}

// The read-only page displaying one submission, registered as a view on the `iap/coreUI/view`
// extension point for `/Submissions/*`. The submission is fetched with the `deep` serialization,
// which also expands the referenced schema version (and its requirements), so the answers can be
// presented grouped the way the schema's forms and sections define, alongside the attached
// documents and the reviews. Editing is deliberately out of scope for now.
function SubmissionView() {
  const location = useLocation();
  // The page URL is the submission's repository path (a trailing .html is tolerated)
  const path = location.pathname.replace(/\.html$/, "");
  const [submission, setSubmission] = useState<JsonNode>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetch(`${path}.deep.json`)
      .then(response => {
        if (!response.ok) {
          throw new Error(`This submission cannot be loaded (${response.status})`);
        }
        return response.json() as Promise<JsonNode>;
      })
      .then(json => {
        if (!cancelled) {
          setSubmission(json);
          setError(undefined);
        }
      })
      .catch((e: Error) => {
        if (!cancelled) {
          setError(e.message);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [path]);

  if (loading) {
    return <LoadingOverlay open />;
  }
  if (error || !submission) {
    return <Alert severity="error">{error ?? "This submission cannot be displayed"}</Alert>;
  }

  const schemaVersion = isNode(submission.schemaVersion) ? submission.schemaVersion : undefined;
  const answers = childrenOfType(submission, "sub/Answer");
  const documents = childrenOfType(submission, "sub/Document");
  const reviews = childrenOfType(submission, "sub/Review");
  const forms = schemaVersion ? childrenOfType(schemaVersion, "sch/FormRequirement") : [];
  const documentRequirements = schemaVersion ? childrenOfType(schemaVersion, "sch/DocumentRequirement") : [];
  const missingDocuments = "No documents attached yet"
    + (documentRequirements.length > 0
      ? `; expected: ${documentRequirements.map(requirement => String(requirement.label)).join(", ")}`
      : "");

  return (
    <Stack spacing={2}>
      <Box>
        <Link component={RouterLink} to="/">← Back to the dashboard</Link>
        <Stack direction="row" spacing={2} sx={{ alignItems: "center", mt: 1 }}>
          <Typography variant="h4">{String(submission.title ?? submission["@name"])}</Typography>
          <StatusChip value={submission.status} />
        </Stack>
        <Typography color="text.secondary">
          {schemaLabel(schemaVersion)}
          {submission["jcr:created"]
            ? ` • Created ${formatDate(submission["jcr:created"])} by ${String(submission["jcr:createdBy"] ?? "")}`
            : ""}
          {submission["jcr:lastModified"] ? ` • Last modified ${formatDate(submission["jcr:lastModified"])}` : ""}
        </Typography>
      </Box>
      {forms.map((form, index) => (
        <Section
          key={"form-" + index}
          title={String(form.label ?? form["@name"])}
          subtitle={form.description ? String(form.description) : undefined}
        >
          <FormItems container={form} answers={answers} level={0} />
        </Section>
      ))}
      <Section title="Documents">
        {documents.length > 0
          ? <Documents documents={documents} />
          : <Typography color="text.secondary">{missingDocuments}</Typography>}
      </Section>
      <Section title="Reviews">
        {reviews.length > 0
          ? <Reviews reviews={reviews} />
          : <Typography color="text.secondary">No reviews yet</Typography>}
      </Section>
    </Stack>
  );
}

export default SubmissionView;
