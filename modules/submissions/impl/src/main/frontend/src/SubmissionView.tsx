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

import { useEffect, useRef, useState } from "react";

import EditIcon from "@mui/icons-material/Edit";
import VisibilityIcon from "@mui/icons-material/Visibility";
import {
  Alert,
  Box,
  CircularProgress,
  Divider,
  Link,
  Stack,
  ToggleButton,
  ToggleButtonGroup,
  Typography
} from "@mui/material";
import { Link as RouterLink, useLocation, useNavigate } from "react-router";

import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import Panel from "@iap/frontend-commons/components/Panel";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, RequestError } from "@iap/frontend-commons/requestFailure";
import { type SerializedNode, childrenOfType, isNode } from "@iap/frontend-commons/serializedNode";
import { usePendingWork } from "@iap/frontend-commons/usePendingWork";
import TagChip from "@iap/tags/TagChip";

import SubmissionEditor from "./SubmissionEditor";
import {
  type DocumentRequirement,
  type SubmissionForm,
  describeNothingAttached,
  fetchForm,
  isDocumentRequirement,
  toFileUrl
} from "./submissionForm";
import { schemaLabel } from "./submissionGrid";
import SubmissionTasks from "./SubmissionTasks";

// The extension that asks for the editor rather than the read-only page
const EDIT = ".edit";

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

// Who raised this submission, as the engine recorded it. Seeded content has no `createdBy`, so its writer
// is named instead.
function createdBy(submission: SerializedNode): unknown {
  return submission.createdBy ?? submission["jcr:createdBy"];
}

// JCR dates are serialized as ISO 8601 strings; anything else is not a date
function formatDate(value: unknown): string {
  return typeof value === "string" && value !== "" ? new Date(value).toLocaleString() : "";
}

// What a stored value reads as: the label of the option it picks, where the question offers one.
function labelled(question: SerializedNode, value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(entry => labelled(question, entry));
  }
  const option = childrenOfType(question, "sch/AnswerOption").find(child => child.value === value);
  return option === undefined ? value : option.label ?? option.value;
}

// One question with its answer (or a placeholder when unanswered).
function QuestionRow({ question, answers }: { question: SerializedNode; answers: SerializedNode[] }) {
  const answer = answers.find(candidate =>
    isNode(candidate.question) && candidate.question["@path"] === question["@path"]);
  const value = answer && formatValue(labelled(question, answer.value));
  return (
    <Box>
      <Typography variant="subtitle2">{String(question.text ?? question["@name"])}</Typography>
      {value
        ? <Typography>{value}</Typography>
        : <Typography variant="placeholder">Not answered yet</Typography>}
    </Box>
  );
}

// The items of a form or section: questions, and nested sections with their own headings.
function FormItems(
  { container, answers, level }: { container: SerializedNode; answers: SerializedNode[]; level: number },
) {
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
                ? <Typography variant="description">{formatValue(item.description)}</Typography>
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

// The `sub:File` of a document's newest version. Older versions are its history and stay unshown.
function currentFile(document: SerializedNode): SerializedNode | undefined {
  const file = childrenOfType(document, "sub/DocumentVersion").at(-1)?.file;
  return isNode(file) && isNode(file.uploadedFile) ? file : undefined;
}

// One attached document: what it is called, as a link to download its current file.
function Attachment({ document, named }: { document: SerializedNode; named: boolean }) {
  const requirement = isNode(document.fulfills) ? document.fulfills : undefined;
  const title = String(document.title ?? document["@name"]);
  const file = currentFile(document);
  // A reference is serialized with whatever the referenced node holds, and a requirement need not
  // carry a label. Worth saying only where the grouping does not already say it, and only where
  // there is something to say: `fulfills "undefined"` is worse than nothing at all.
  const fulfills = named && typeof requirement?.label === "string" ? requirement.label : undefined;
  return (
    <Box>
      <Typography variant="subtitle2">
        {/* The stored file is always called `uploadedFile`, so the download is named after the title */}
        {file
          ? <Link href={toFileUrl(`${String(file["@path"])}/uploadedFile`)} download={title}>{title}</Link>
          : title}
        {fulfills ? ` — fulfills "${fulfills}"` : ""}
      </Typography>
      {document.description
        ? <Typography variant="description">{formatValue(document.description)}</Typography>
        : null}
    </Box>
  );
}

// What the schema asks for and what has been attached against it.
//
// The requirements come from the form projection rather than from the submission this page already
// holds, because a document requirement can be conditional, and conditions are resolved on the
// server by design. Reading them off the schema instead would list documents this request was never
// asked for.
function Documents({ path, documents }: { path: string; documents: SerializedNode[] }) {
  const [form, setForm] = useState<SubmissionForm | undefined>(undefined);
  const [failure, setFailure] = useState<string | undefined>(undefined);
  const doFetch = useAuthenticatedFetch();

  // The page rebuilds this section for each request it shows, so a projection can only ever land on
  // the request it was asked for
  useEffect(() => {
    fetchForm(doFetch, path).then(setForm, (error: unknown) => setFailure(describeRequestFailure(error)));
  }, [doFetch, path]);

  // Until the projection arrives nothing can be said about what was asked
  if (!form && !failure) {
    return <CircularProgress size={24} aria-label="Loading the documents" />;
  }
  // A failed projection still leaves what is attached, which comes from the submission itself
  const warning = failure
    ? <Alert severity="warning">{`Which documents this request asks for could not be read: ${failure}`}</Alert>
    : null;

  const requirements = (form?.requirements ?? []).filter(isDocumentRequirement);
  const fulfilling = (requirement: DocumentRequirement) => documents.filter(document =>
    isNode(document.fulfills) && document.fulfills["@path"] === requirement.path);
  // Anything whose requirement does not currently apply, is gone from the schema, or that never named
  // one: still somebody's evidence, so shown rather than silently dropped
  const claimed = new Set(requirements.flatMap(requirement =>
    fulfilling(requirement).map(document => document["@path"])));
  const unattributed = documents.filter(document => !claimed.has(document["@path"]));

  if (requirements.length === 0 && documents.length === 0) {
    return warning ?? <Typography variant="placeholder">This request asks for no documents</Typography>;
  }

  return (
    <Stack spacing={2} divider={<Divider />}>
      {warning}
      {requirements.map(requirement => {
        const attached = fulfilling(requirement);
        return (
          <Stack key={requirement.name} spacing={1}>
            <Typography variant="subtitle1">{requirement.label || requirement.name}</Typography>
            {requirement.description
              ? <Typography variant="description">{requirement.description}</Typography>
              : null}
            {attached.length > 0
              ? attached.map((document, position) =>
                <Attachment key={"attached-" + position} document={document} named={false} />)
              : <Typography variant="placeholder">{describeNothingAttached(requirement)}</Typography>}
          </Stack>
        );
      })}
      {unattributed.map((document, index) =>
        <Attachment key={"other-" + index} document={document} named />)}
    </Stack>
  );
}

// The reviews added to the submission, each with its threaded comments.
function Reviews({ reviews }: { reviews: SerializedNode[] }) {
  return (
    <Stack spacing={2} divider={<Divider />}>
      {reviews.map((review, index) => {
        const requirement = isNode(review.requirement) ? review.requirement : undefined;
        const comments = childrenOfType(review, "sub/Comment");
        return (
          <Box key={"review-" + index}>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Typography variant="subtitle2">{String(review.reviewer)}</Typography>
              {requirement ? <Typography variant="description">on {String(requirement.label)}</Typography> : null}
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
// which also expands the referenced schema version and its requirements. The answers can then be
// grouped the way the schema's forms and sections define, alongside the attached documents and the
// reviews.
function SubmissionView() {
  const location = useLocation();
  const navigate = useNavigate();
  // The page URL is the submission's repository path (a trailing .html is tolerated). A trailing
  // `.edit` asks for the editor. Which view is shown is addressed the way every other view here is:
  // by extension rather than by a query parameter. The server serves the same shell for it.
  const address = location.pathname.replace(/\.html$/, "");
  const editing = address.endsWith(EDIT);
  const path = editing ? address.slice(0, -EDIT.length) : address;
  const [submission, setSubmission] = useState<SerializedNode>();
  const [error, setError] = useState<string>();
  // Loading is derived, not toggled inside the fetch effect: the view is loading until the
  // fetch for the currently displayed path has settled, one way or the other
  const [loadedPath, setLoadedPath] = useState<string>();
  const loading = loadedPath !== path;
  const fetchUtil = useAuthenticatedFetch();
  // The editor's saves still on their way, which completing a step must not overtake
  const saves = usePendingWork();
  // The submission on screen, or none once the page is gone: a step completed after the reader moved
  // on must not bring them back
  const shownPath = useRef<string | undefined>(path);
  useEffect(() => {
    shownPath.current = path;
    return () => {
      shownPath.current = undefined;
    };
  }, [path]);
  // Bumped when something else on the page changes the submission, so the fetch below runs again for
  // the same path
  const [reloads, setReloads] = useState(0);

  // Read in both modes: the steps offered above them depend on the submission, which changes while it
  // is filled in. Switching modes reads it again, so that leaving the editor shows what it changed.
  useEffect(() => {
    let cancelled = false;
    fetchUtil(`${path}.deep.json`)
      .then(response => {
        if (!response.ok) {
          throw new RequestError(response.status);
        }
        return response.json() as Promise<SerializedNode>;
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
  }, [path, fetchUtil, editing, reloads]);

  // Reading and filling in are two modes of the same page, so the way between them belongs to the
  // page rather than to either mode. It is rendered whatever the page is doing, because the states
  // with nothing to show are exactly the ones somebody needs a way out of. Before this, the
  // editor was reachable only from a listing and, once open, offered no way back at all.
  const header = (
    <Stack direction="row" spacing={2} sx={{ alignItems: "center", justifyContent: "space-between" }}>
      <Link component={RouterLink} to="/">← Back to the dashboard</Link>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        {/* What the process is waiting for, offered in both modes */}
        <SubmissionTasks
          path={path}
          beforeComplete={saves.allDone}
          onCompleted={() => {
            if (shownPath.current !== path) {
              return;
            }
            // Back to reading it: a completed step usually leaves the submission read-only
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
        {/* Keyed, so navigating to another submission builds a new editor rather than showing
            the previous one's answers until the new form lands */}
        <SubmissionEditor key={path} path={path} onSaving={saves.track} />
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
        <Typography variant="description">
          {schemaLabel(schemaVersion)}
          {submission["jcr:created"]
            ? ` • Created ${formatDate(submission["jcr:created"])} by ${formatValue(createdBy(submission))}`
            : ""}
          {submission["jcr:lastModified"] ? ` • Last modified ${formatDate(submission["jcr:lastModified"])}` : ""}
        </Typography>
      </Box>
      {forms.map((form, index) => (
        <Panel
          key={"form-" + index}
          title={String(form.label ?? form["@name"])}
          subtitle={form.description ? formatValue(form.description) : undefined}
        >
          <FormItems container={form} answers={answers} level={0} />
        </Panel>
      ))}
      <Panel title="Documents">
        <Documents path={path} documents={documents} />
      </Panel>
      <Panel title="Reviews">
        {reviews.length > 0
          ? <Reviews reviews={reviews} />
          : <Typography variant="placeholder">No reviews yet</Typography>}
      </Panel>
    </Stack>
  );
}

export default SubmissionView;
