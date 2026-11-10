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

import { useCallback, useEffect, useRef, useState } from "react";

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

import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import Panel from "@iap/frontend-commons/components/Panel";
import { formatDate } from "@iap/frontend-commons/dates";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, readJson, RequestError } from "@iap/frontend-commons/requestFailure";
import { type SerializedNode, childrenOfType, isNode } from "@iap/frontend-commons/serializedNode";
import { useNode } from "@iap/frontend-commons/useNode";
import { usePendingWork } from "@iap/frontend-commons/usePendingWork";
import { carriesTag } from "@iap/tags/carriesTag";
import TagChip from "@iap/tags/TagChip";

import ApprovalState from "./ApprovalState";
import ReadingBanner from "./ReadingBanner";
import SubmissionEditor from "./SubmissionEditor";
import {
  type DocumentRequirement,
  type Requirement,
  type SubmissionForm,
  describeNothingAttached,
  isApprovalRequirement,
  isBeingRead,
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
  // Anything else, such as a nested node or a missing value, reads as no answer
  return ["string", "number"].includes(typeof value) ? String(value) : "";
}

// Who raised the submission, as the engine recorded it. Content the engine did not create has no
// `createdBy`, and its writer is named instead.
function createdBy(submission: SerializedNode): unknown {
  return submission.createdBy ?? submission["jcr:createdBy"];
}

// What a stored value reads as: the label of the option it picks, when the question offers one.
function labelled(question: SerializedNode, value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(entry => labelled(question, entry));
  }
  const option = childrenOfType(question, "sch/AnswerOption").find(child => child.value === value);
  return option === undefined ? value : option.label ?? option.value;
}

// One question with its answer, or with a placeholder when it is unanswered.
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

// The `sub:File` of a document's newest version, which is its last; undefined until a file is
// uploaded. The older versions are not shown.
function currentFile(document: SerializedNode): SerializedNode | undefined {
  const file = childrenOfType(document, "sub/DocumentVersion").at(-1)?.file;
  return isNode(file) && isNode(file.uploadedFile) ? file : undefined;
}

// One attached document: its title, as a link to download its current file. When `named`, it also
// says which requirement it fulfills.
function Attachment({ document, named }: { document: SerializedNode; named: boolean }) {
  const requirement = isNode(document.fulfills) ? document.fulfills : undefined;
  const title = String(document.title ?? document["@name"]);
  const file = currentFile(document);
  // A requirement need not carry a label
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

// The served form, read as a form. A missing list of requirements reads as empty, so that a malformed
// answer does not take the page down.
function asForm(node: Record<string, unknown>): SubmissionForm {
  const requirements = Array.isArray(node.requirements) ? node.requirements as Requirement[] : [];
  return { ...node, requirements } as SubmissionForm;
}

// A requirement's name and what it says about itself, above whatever answers it
function RequirementHeading({ requirement }: { requirement: Requirement }) {
  return (
    <>
      <Typography variant="subtitle1">{requirement.label || requirement.name}</Typography>
      {requirement.description
        ? <Typography variant="description">{requirement.description}</Typography>
        : null}
    </>
  );
}

// The documents the request asks for, and what has been attached for each. Without the form, the
// attached documents are still shown, since they come from the submission itself.
function Documents({ form, loading, documents }: {
  form: SubmissionForm | undefined;
  loading: boolean;
  documents: SerializedNode[];
}) {
  if (loading) {
    return <CircularProgress size={24} aria-label="Loading the documents" />;
  }
  const requirements = (form?.requirements ?? []).filter(isDocumentRequirement);
  const fulfilling = (requirement: DocumentRequirement) => documents.filter(document =>
    isNode(document.fulfills) && document.fulfills["@path"] === requirement.path);
  // A document fulfilling no requirement listed here, such as one that does not apply now, is still
  // shown, after the requirements
  const claimed = new Set(requirements.flatMap(requirement =>
    fulfilling(requirement).map(document => document["@path"])));
  const unattributed = documents.filter(document => !claimed.has(document["@path"]));

  if (requirements.length === 0 && documents.length === 0) {
    return (
      <Typography variant="placeholder">
        {form ? "This request asks for no documents" : "No documents attached"}
      </Typography>
    );
  }

  return (
    <Stack spacing={2} divider={<Divider />}>
      {requirements.map(requirement => {
        const attached = fulfilling(requirement);
        return (
          <Stack key={requirement.name} spacing={1}>
            <RequirementHeading requirement={requirement} />
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

// The approvals this request needs, and where each of them stands.
function Approvals({ form }: { form: SubmissionForm | undefined }) {
  if (!form) {
    return <CircularProgress size={24} aria-label="Loading the approvals" />;
  }
  const approvals = form.requirements.filter(isApprovalRequirement);
  if (approvals.length === 0) {
    return <Typography variant="placeholder">This request needs no approvals</Typography>;
  }
  return (
    <Stack spacing={2} divider={<Divider />}>
      {approvals.map(approval => (
        <Stack key={approval.name} spacing={1}>
          <RequirementHeading requirement={approval} />
          <ApprovalState requirement={approval} />
        </Stack>
      ))}
    </Stack>
  );
}

// The documents and approvals a request is asked for. Both come from the form the server serves,
// since a requirement can be conditional and the server resolves the conditions. The form is read
// again whenever `reloads` changes, which is when the page reads the submission again.
function DocumentsAndApprovals({ path, documents, reloads }: {
  path: string;
  documents: SerializedNode[];
  reloads: number;
}) {
  const { value: form, loading, loadError, reload } = useNode(path, "form", asForm);
  const readFor = useRef(reloads);
  useEffect(() => {
    if (readFor.current !== reloads) {
      readFor.current = reloads;
      void reload();
    }
  }, [ reloads, reload ]);

  return (
    <>
      {loadError
        ? <LoadError title="What this request asks for could not be read" message={loadError} onRetry={reload} />
        : null}
      <Panel title="Documents">
        <Documents form={form} loading={loading} documents={documents} />
      </Panel>
      {/* Without the form, nothing is known about the approvals */}
      {loading || form
        ? <Panel title="Approvals"><Approvals form={form} /></Panel>
        : null}
    </>
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

// The system tag on a part of a submission that still lacks something. It is aggregated, so the
// submission carries it while any of its parts does.
const INCOMPLETE_TAG = "incomplete";

const INCOMPLETE_REASON = "Answer everything this request asks for first.";

const READING_REASON = "Wait until the document has been read.";

const BLOCKED_REASON = "The uploaded document does not let this request go on.";

// How often to fetch the submission again while a document is being read, to see when the reading ends
const READING_RECHECK_MILLISECONDS = 3000;

// Whether a document is being read for answers. The next step waits until the reading ends.
function isReading(submission: SerializedNode | undefined): boolean {
  return isBeingRead(submission?.extractionStatus);
}

// Why the submission is not ready for its next step, or nothing when it is
// Whether a reading of the documents was ever asked for, so there is something to say about it
function isReadingAsked(submission: SerializedNode | undefined): boolean {
  return typeof submission?.extractionStatus === "string";
}

function whyNotReady(submission: SerializedNode | undefined): string | undefined {
  if (isReading(submission)) {
    return READING_REASON;
  }
  // A reading can end with the request unable to go on, such as for a document that is not what it asks for
  if (submission?.extractionBlocked === true) {
    return typeof submission.extractionMessage === "string" ? submission.extractionMessage : BLOCKED_REASON;
  }
  return carriesTag(submission, INCOMPLETE_TAG) ? INCOMPLETE_REASON : undefined;
}

// The page of one submission: read-only, or the editor at `<path>.edit`. The read-only page groups
// the answers by the schema's forms and sections, and shows the documents, the approvals and the
// reviews. The submission is read with the `deep` serialization, which also expands the schema
// version it answers.
function SubmissionView() {
  const location = useLocation();
  const navigate = useNavigate();
  // The page URL is the submission's repository path, with an optional `.html`. A trailing `.edit`
  // asks for the editor, and the server serves the same page for it.
  const address = location.pathname.replace(/\.html$/, "");
  const editing = address.endsWith(EDIT);
  const path = editing ? address.slice(0, -EDIT.length) : address;
  const [submission, setSubmission] = useState<SerializedNode>();
  const [error, setError] = useState<string>();
  // The page is loading until the read of the current path settles, one way or the other
  const [loadedPath, setLoadedPath] = useState<string>();
  const loading = loadedPath !== path;
  const fetchUtil = useAuthenticatedFetch();
  // The editor's saves still on their way, which completing a step must not overtake
  const { track: trackSave, allDone: savesDone } = usePendingWork();
  // The path of the submission on screen; undefined once the page is gone. A step completed after the
  // reader moved on must not bring them back.
  const shownPath = useRef<string | undefined>(path);
  useEffect(() => {
    shownPath.current = path;
    return () => {
      shownPath.current = undefined;
    };
  }, [path]);
  // Bumped when something on the page changes the submission, so that the read below runs again for
  // the same path
  const [reloads, setReloads] = useState(0);
  const readAgain = useCallback(() => setReloads(current => current + 1), []);

  // Waits for the answers still being saved, then reads the submission again, since they may change
  // what it still lacks. Rejects with the reason when the submission is not ready for its next step.
  const readyForNextStep = useCallback(() => savesDone()
    .then(() => fetchUtil(`${path}.json`))
    .then(response => {
      if (!response.ok) {
        throw new RequestError(response.status);
      }
      return readJson<SerializedNode>(response);
    })
    .then(fresh => {
      const reason = whyNotReady(fresh);
      if (reason !== undefined) {
        throw new Error(reason);
      }
    }), [ savesDone, fetchUtil, path ]);

  // Read in both modes. Whether the waiting step can be taken depends on what the request still lacks,
  // which changes while it is filled in.
  useEffect(() => {
    let cancelled = false;
    fetchUtil(`${path}.deep.json`)
      .then(response => {
        if (!response.ok) {
          throw new RequestError(response.status);
        }
        return readJson<SerializedNode>(response);
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

  // The server does not tell the page when a reading ends, so fetch the submission again until it has
  const reading = isReading(submission);
  useEffect(() => {
    if (!reading) {
      return undefined;
    }
    const timer = setTimeout(readAgain, READING_RECHECK_MILLISECONDS);
    return () => clearTimeout(timer);
  }, [reading, submission, readAgain]);

  // The way back, the steps the request is waiting for, and the switch between the two modes. It is
  // shown in every state of the page. Loading and failure are where somebody most needs a way out.
  const header = (
    <Stack direction="row" spacing={2} sx={{ alignItems: "center", justifyContent: "space-between" }}>
      <Link component={RouterLink} to="/">← Back to the dashboard</Link>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <SubmissionTasks
          path={path}
          readinessNote={whyNotReady(submission)}
          blocked={reading}
          beforeComplete={readyForNextStep}
          onCompleted={() => {
            if (shownPath.current !== path) {
              return;
            }
            // Back to the read-only page, read again
            readAgain();
            void navigate(path);
          }}
        />
        <ToggleButtonGroup
          exclusive
          value={editing ? "edit" : "view"}
          // An exclusive group reports null when the selected button is pressed again, and the page
          // then stays as it is
          onChange={(_event, next: string | null) => {
            if (next) {
              // Read again, so that leaving the editor shows what it changed
              readAgain();
              void navigate(next === "edit" ? `${path}${EDIT}` : path);
            }
          }}
          aria-label="How to show this submission"
        >
          <ToggleButton value="view">
            <VisibilityIcon fontSize="small" sx={{ mr: 0.5 }} />
            View
          </ToggleButton>
          {/* Offered to every reader. The form the server serves says whether this reader may edit,
              and the editor says so when they may not. */}
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
        {isReadingAsked(submission) && <ReadingBanner path={path} reloads={reloads} onChanged={readAgain} />}
        {/* Keyed by path, so that another submission gets a new editor rather than showing the
            previous one's answers until its form arrives */}
        <SubmissionEditor key={path} path={path} reading={reading} onSaving={trackSave} onChanged={readAgain} />
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
      {isReadingAsked(submission) && <ReadingBanner path={path} reloads={reloads} onChanged={readAgain} />}
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
      <DocumentsAndApprovals path={path} documents={documents} reloads={reloads} />
      <Panel title="Reviews">
        {reviews.length > 0
          ? <Reviews reviews={reviews} />
          : <Typography variant="placeholder">No reviews yet</Typography>}
      </Panel>
    </Stack>
  );
}

export default SubmissionView;
