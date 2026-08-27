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

import EditIcon from "@mui/icons-material/Edit";
import VisibilityIcon from "@mui/icons-material/Visibility";
import {
  Alert,
  Box,
  Divider,
  Link,
  Paper,
  Stack,
  ToggleButton,
  ToggleButtonGroup,
  Typography
} from "@mui/material";
import { Link as RouterLink, useLocation, useNavigate } from "react-router";

import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, RequestError } from "@iap/frontend-commons/requestFailure";
import TagChip from "@iap/tags/TagChip";

import ApprovalState from "./ApprovalState";
import { type JsonNode, childrenOfType, isNode } from "./jsonNode";
import SubmissionEditor from "./SubmissionEditor";
import {
  APPROVAL_REQUIREMENT, DOCUMENT_REQUIREMENT, type FormRequirement, type SubmissionForm, fetchForm,
  formatDate,
} from "./submissionForm";
import { schemaLabel } from "./submissionGrid";
import SubmissionTasks from "./SubmissionTasks";

// The extension that asks for the editor rather than the read-only page
const EDIT = ".edit";

// The tag the save workflow places when something the schema asks for has not been answered
const INCOMPLETE = "incomplete";


// A single-valued property is serialized as a bare string, not as a one-element array.
function asList(value: unknown): string[] {
  if (Array.isArray(value)) {
    return (value as unknown[]).filter((entry): entry is string => typeof entry === "string");
  }
  return typeof value === "string" ? [value] : [];
}

// Whether the request is still missing an answer, read from the submission this page already holds
// rather than by asking for its form: the save workflow worked it out and recorded it.
function isIncomplete(submission: JsonNode | undefined): boolean {
  return asList(submission?.tags).includes(INCOMPLETE);
}



function formatValue(value: unknown): string {
  if (Array.isArray(value)) {
    return value.map(entry => formatValue(entry)).join(", ");
  }
  if (typeof value === "boolean") {
    return value ? "Yes" : "No";
  }
  // Anything else (nested objects, missing values) has no meaningful text form
  return ["string", "number"].includes(typeof value) ? String(value) : "";
}

// A repository path plus a file name as a usable URL: every segment percent-encoded, so
// names containing #, ? or % survive as path characters instead of being parsed as syntax
function fileHref(path: unknown, name: string): string {
  return [...String(path).split("/"), name].map(encodeURIComponent).join("/");
}

// Who raised this submission. `createdBy` rather than `jcr:createdBy`, and for the same reason the
// dashboard's "my submissions" filter selects on it: the engine writes every submission as its own
// service user, so the JCR property credits the engine. It is still the fallback here, where the Java
// model's own `getCreatedBy()` puts it — a page saying "Created by" and then nothing is worse than one
// naming whoever did write it, which for seeded content is all there is to say.
function createdBy(submission: JsonNode): unknown {
  return submission.createdBy ?? submission["jcr:createdBy"];
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
              {item.description
                ? <Typography color="text.secondary">{formatValue(item.description)}</Typography>
                : null}
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

// One attached document: what it is called and links to download whatever files it holds.
function Attachment({ document, named }: { document: JsonNode; named: boolean }) {
  const requirement = isNode(document.fulfills) ? document.fulfills : undefined;
  const files = Object.entries(document)
    .filter(([, value]) => isNode(value) && value["jcr:primaryType"] === "nt:file");
  // A reference is serialized with whatever the referenced node holds, and a requirement need not
  // carry a label. Worth saying only where the grouping does not already say it, and only where
  // there is something to say: `fulfills "undefined"` is worse than nothing at all.
  const fulfills = named && typeof requirement?.label === "string" ? requirement.label : undefined;
  return (
    <Box>
      <Typography variant="subtitle2">
        {String(document.title ?? document["@name"])}
        {fulfills ? ` — fulfills "${fulfills}"` : ""}
      </Typography>
      {document.description
        ? <Typography color="text.secondary">{formatValue(document.description)}</Typography>
        : null}
      <Stack>
        {files.map(([name]) =>
          <Link key={name} href={fileHref(document["@path"], name)} download>{name}</Link>)}
      </Stack>
    </Box>
  );
}

// What the schema asks for and what has been attached against it. Reading only: a document is
// attached while the request is being filled in, which is what the editor is, so this is the page
// that says where things stand rather than a second way to change them.
//
// The requirements come from the form projection rather than from the submission this page already
// holds, because a requirement can be conditional: the demo asks for a doctor's note only for sick
// leave, and conditions are resolved on the server by design. Reading them off the schema instead
// would list a doctor's note on a holiday request.
function Documents({ form, documents }: {
  form: SubmissionForm | undefined;
  documents: JsonNode[];
}) {
  const requirements = (form?.requirements ?? [])
    .filter(requirement => requirement.type === DOCUMENT_REQUIREMENT);
  const fulfilling = (requirement: FormRequirement) => documents.filter(document =>
    isNode(document.fulfills) && document.fulfills["@name"] === requirement.name);
  // Anything whose requirement does not currently apply, is gone from the schema, or that never named
  // one: still somebody's evidence, so shown rather than silently dropped
  const claimed = new Set(requirements.flatMap(requirement =>
    fulfilling(requirement).map(document => document["@path"])));
  const unattributed = documents.filter(document => !claimed.has(document["@path"]));

  if (requirements.length === 0 && documents.length === 0) {
    return <Typography color="text.secondary">This request asks for no documents</Typography>;
  }

  return (
    <Stack spacing={2} divider={<Divider />}>
      {requirements.map(requirement => {
        const attached = fulfilling(requirement);
        return (
          <Stack key={requirement.name} spacing={1}>
            <Typography variant="subtitle1">{requirement.label || requirement.name}</Typography>
            {requirement.description
              ? <Typography color="text.secondary">{requirement.description}</Typography>
              : null}
            {attached.length > 0
              ? attached.map((document, position) =>
                <Attachment key={"attached-" + position} document={document} named={false} />)
              : <Typography color="text.secondary">Nothing attached yet</Typography>}
          </Stack>
        );
      })}
      {unattributed.map((document, index) =>
        <Attachment key={"other-" + index} document={document} named />)}
    </Stack>
  );
}

// The approvals this request needs, and where each of them stands. Read from the same projection the
// editor reads, so the two modes cannot disagree about what is still waiting — and shown in view mode
// because a request parked on somebody else's decision is exactly what a reader has come to find out.
function Approvals({ requirements }: { requirements: FormRequirement[] }) {
  if (requirements.length === 0) {
    return <Typography color="text.secondary">This request needs no approvals</Typography>;
  }
  return (
    <Stack spacing={2} divider={<Divider />}>
      {requirements.map(requirement => (
        <Stack key={requirement.name} spacing={1}>
          <Typography variant="subtitle1">{requirement.label || requirement.name}</Typography>
          {requirement.description
            ? <Typography color="text.secondary">{requirement.description}</Typography>
            : null}
          <ApprovalState requirement={requirement} />
        </Stack>
      ))}
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
              <TagChip tags={review.tags} category="review" />
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
  const navigate = useNavigate();
  // The page URL is the submission's repository path (a trailing .html is tolerated). A trailing
  // `.edit` asks for the editor: which view of a submission is shown is addressed the way every
  // other view here is, by extension rather than by a query parameter, and the server serves the
  // same shell for it.
  const address = location.pathname.replace(/\.html$/, "");
  const editing = address.endsWith(EDIT);
  const path = editing ? address.slice(0, -EDIT.length) : address;
  const [submission, setSubmission] = useState<JsonNode>();
  // The form projection, read once for the whole page: two sections ask what this request is being
  // asked for — the documents and the approvals — and a requirement can be conditional, so neither
  // can read it off the schema. Fetching it in each of them would ask the server the same question
  // twice and let the two disagree while one of the answers was still in flight.
  const [form, setForm] = useState<SubmissionForm | undefined>(undefined);
  const [error, setError] = useState<string>();
  // Loading is derived, not toggled inside the fetch effect: the view is loading until the
  // fetch for the currently displayed path has settled, one way or the other
  const [loadedPath, setLoadedPath] = useState<string>();
  const loading = loadedPath !== path;
  const fetchUtil = useAuthenticatedFetch();
  // Bumped when something else on the page changes the submission, so that the fetch below runs
  // again for a path it has already loaded — which is the one thing its own dependencies cannot say
  const [reloads, setReloads] = useState(0);

  useEffect(() => {
    let cancelled = false;
    // A projection that cannot be read leaves those sections showing what is there and saying nothing
    // about what was asked, which is the half that can still be trusted
    fetchForm(path).then(
      next => {
        if (!cancelled) {
          setForm(next);
        }
      },
      () => {
        if (!cancelled) {
          setForm(undefined);
        }
      }
    );
    return () => {
      cancelled = true;
    };
  }, [path, reloads]);

  // Read in both modes, because the step offered above the two of them is decided by what the request
  // is still missing, and that changes while somebody is filling it in. Skipping the read while the
  // editor was open left that control refusing a request that had just been completed — for the whole
  // editing session, since nothing else re-read the page. The editor says when it has changed
  // something rather than this guessing, so the extra read costs one request per editor opened.
  useEffect(() => {
    let cancelled = false;
    fetchUtil(`${path}.deep.json`)
      .then(response => {
        if (!response.ok) {
          throw new RequestError(response.status);
        }
        return response.json() as Promise<JsonNode>;
      })
      .then(json => {
        if (!cancelled) {
          setSubmission(json);
          setError(undefined);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(describeRequestFailure(e));
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoadedPath(path);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [path, fetchUtil, reloads]);

  // Reading and filling in are two modes of the same page, so the way between them belongs to the
  // page rather than to either mode — and it is rendered whatever the page is doing, because the
  // states with nothing to show are exactly the ones somebody needs a way out of. Before this, the
  // editor was reachable only from a listing and, once open, offered no way back at all.
  const header = (
    <Stack direction="row" spacing={2} sx={{ alignItems: "center", justifyContent: "space-between" }}>
      <Link component={RouterLink} to="/">← Back to the dashboard</Link>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        {/* Whatever the process is waiting for, offered where the page's other actions are and in
            both modes. Sending a request is a step of its workflow, so it belongs beside the way of
            looking at it rather than at the bottom of one of the two views. */}
        <SubmissionTasks
          path={path}
          blockedReason={isIncomplete(submission)
            ? "Answer everything this request asks for before sending it."
            : undefined}
          onCompleted={() => {
            // Back to reading it: what was just done has usually made it read-only, and it is what
            // has changed that the person now wants to see
            setReloads(current => current + 1);
            void navigate(path);
          }}
        />
        <ToggleButtonGroup
          exclusive
          value={editing ? "edit" : "view"}
          // An exclusive group reports null when the selected button is clicked again. That is a
          // deselection, and there is no third mode to land in, so it leaves the page as it is.
          onChange={(_event, next: string | null) => {
            if (next) {
              void navigate(next === "edit" ? `${path}${EDIT}` : path);
            }
          }}
          aria-label="How to show this submission"
        >
          <ToggleButton value="view">
            <VisibilityIcon fontSize="small" sx={{ mr: 0.5 }} />
            View
          </ToggleButton>
          {/* Offered to whoever is looking. Whether it can actually be edited is the server's answer,
              given by the form it serves, and the editor says so plainly when it may not be — the same
              rule the listing's Edit action follows. */}
          <ToggleButton value="edit">
            <EditIcon fontSize="small" sx={{ mr: 0.5 }} />
            Edit
          </ToggleButton>
        </ToggleButtonGroup>
      </Stack>
    </Stack>
  );

  if (editing) {
    return (
      <Stack spacing={2}>
        {header}
        {/* Answering or attaching can be the thing that completes the request, and whether it is
            complete decides whether the step above offers to send it. Re-read here rather than
            worked out again: the save workflow already recorded it on the submission. */}
        <SubmissionEditor path={path} onChanged={() => setReloads(current => current + 1)} />
      </Stack>
    );
  }
  if (loading) {
    return (
      <Stack spacing={2}>
        {header}
        <LoadingOverlay open />
      </Stack>
    );
  }
  if (error || !submission) {
    return (
      <Stack spacing={2}>
        {header}
        <Alert severity="error">{error ?? "This submission cannot be displayed"}</Alert>
      </Stack>
    );
  }

  const schemaVersion = isNode(submission.schemaVersion) ? submission.schemaVersion : undefined;
  const answers = childrenOfType(submission, "sub/Answer");
  const documents = childrenOfType(submission, "sub/Document");
  const reviews = childrenOfType(submission, "sub/Review");
  const forms = schemaVersion ? childrenOfType(schemaVersion, "sch/FormRequirement") : [];

  return (
    <Stack spacing={2}>
      {header}
      <Box>
        <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
          <Typography variant="h4">{String(submission.title ?? submission["@name"])}</Typography>
          <TagChip tags={submission.tags} category="lifecycle" />
        </Stack>
        <Typography color="text.secondary">
          {schemaLabel(schemaVersion)}
          {submission["jcr:created"]
            ? ` • Created ${formatDate(submission["jcr:created"])} by ${formatValue(createdBy(submission))}`
            : ""}
          {submission["jcr:lastModified"] ? ` • Last modified ${formatDate(submission["jcr:lastModified"])}` : ""}
        </Typography>
      </Box>
      {forms.map((form, index) => (
        <Section
          key={"form-" + index}
          title={String(form.label ?? form["@name"])}
          subtitle={form.description ? formatValue(form.description) : undefined}
        >
          <FormItems container={form} answers={answers} level={0} />
        </Section>
      ))}
      <Section title="Documents">
        <Documents form={form} documents={documents} />
      </Section>
      <Section title="Approvals">
        <Approvals requirements={(form?.requirements ?? [])
          .filter(requirement => requirement.type === APPROVAL_REQUIREMENT)}
        />
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
