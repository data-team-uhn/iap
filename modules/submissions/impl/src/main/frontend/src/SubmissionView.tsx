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
  Button,
  CircularProgress,
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
import { describeRequestFailure, messageOf, readJson, RequestError } from "@iap/frontend-commons/requestFailure";
import TagChip from "@iap/tags/TagChip";

import QuestionText from "./answers/QuestionText";
import ApprovalState from "./ApprovalState";
import { type JsonNode, childrenOfType, isNode } from "./jsonNode";
import SubmissionEditor from "./SubmissionEditor";
import {
  APPROVAL_REQUIREMENT, DOCUMENT_REQUIREMENT, FORM_REQUIREMENT, type ExtractionState, type FormItem,
  type FormQuestion, type FormRequirement, type SubmissionForm, fetchForm, formatDate, isQuestion,
  readAgain,
} from "./submissionForm";
import { schemaLabel } from "./submissionGrid";
import SubmissionTasks from "./SubmissionTasks";

// The extension that asks for the editor rather than the read-only page
const EDIT = ".edit";

// The tag the save workflow places when something the schema asks for has not been answered
const INCOMPLETE = "incomplete";

// How often to ask again while the uploaded documents are still being read
const EXTRACTION_POLL_MS = 4000;

// How long to keep asking before giving up on the answer arriving. Comfortably past the server side
// deadline on a parse, so a reading that is merely slow is never given up on here first; the server
// sweep fails a lost parse and the next poll sees that. This is the backstop for a page left open
// against a server that has stopped answering at all, which would otherwise poll for as long as the
// tab is open.
const EXTRACTION_POLL_LIMIT = (45 * 60 * 1000) / EXTRACTION_POLL_MS;


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

// The documents attached against one requirement
function documentsFulfilling(requirement: FormRequirement, documents: JsonNode[]): JsonNode[] {
  return documents.filter(document =>
    isNode(document.fulfills) && document.fulfills["@name"] === requirement.name);
}

// Why the waiting step may not be completed yet, or nothing when it may.
//
// Two things are checked. A document the request insists on that nobody has attached: completing a
// step without it moves the process on with nothing to read, and nothing shows that it happened. And
// the incomplete tag the save placed, but not while an attached document is still to be read: the
// answers the tag counts as missing are the ones the reading fills in, so the step that sends the
// document to be read must not wait for them. While the reading runs, the step waits for it instead.
//
// Only a form whose schema version reads its documents gets that second exemption. Without asking
// that, a request that attaches a document nothing ever reads waits for a reading that will not
// happen, and the incomplete tag stops meaning anything.
function whyBlocked(submission: JsonNode | undefined, form: SubmissionForm | undefined): string | undefined {
  const documents = submission ? childrenOfType(submission, "sub/Document") : [];
  const asked = (form?.requirements ?? []).filter(requirement => requirement.type === DOCUMENT_REQUIREMENT);
  const missing = asked.find(requirement =>
    requirement.required === true && documentsFulfilling(requirement, documents).length === 0);
  if (missing) {
    return `Attach the ${missing.label || missing.name} before going on.`;
  }
  const extraction = form?.extraction?.status;
  if (extraction === "running") {
    return "Wait until the uploaded document has been read.";
  }
  const stillToBeRead = form?.readsDocuments === true && extraction === undefined
    && asked.some(requirement => documentsFulfilling(requirement, documents).length > 0);
  if (stillToBeRead || !isIncomplete(submission)) {
    return undefined;
  }
  return "Answer everything this request asks for before sending it.";
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

// One question with its answer. An unanswered one shows the question alone: a placeholder under every
// open question reads as pre-filled text, and there is nothing to say about an answer that is not there.
//
// A chosen answer is shown as the words that were chosen. What is stored is the option's value, which is
// what a condition compares against - "in-repository", "prom" - and reading those back is reading the
// schema's shorthand rather than the answer.
function QuestionRow({ question }: { question: FormQuestion }) {
  const chosen = question.value
    .map(value => question.options.find(option => option.value === value)?.label ?? value);
  return (
    <Box>
      <QuestionText question={question} labelOnly />
      {chosen.length > 0 ? <Typography>{chosen.join(", ")}</Typography> : null}
    </Box>
  );
}

// The items of a form or section: questions, and nested sections with their own headings.
function FormItems({ items, level }: { items: FormItem[]; level: number }) {
  return (
    <Stack spacing={2}>
      {items.map(item => (isQuestion(item)
        ? <QuestionRow key={item.path} question={item} />
        : (
          <Box key={item.name}>
            <Typography variant={level === 0 ? "subtitle1" : "subtitle2"} sx={{ fontWeight: "bold", mb: 1 }}>
              {item.label || item.name}
            </Typography>
            {item.description ? <Typography variant="description">{item.description}</Typography> : null}
            <FormItems items={item.items} level={level + 1} />
          </Box>
        )))}
    </Stack>
  );
}

// One titled block of the view, rendered as an outlined surface.
function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Typography variant="h6" gutterBottom>{title}</Typography>
      {subtitle ? <Typography variant="description" gutterBottom>{subtitle}</Typography> : null}
      {children}
    </Paper>
  );
}

// One version of a document: the node and the file it holds, when the upload has landed.
interface Upload {
  version: JsonNode;
  file: JsonNode;
}

// The versions of a document that hold an upload, in the order they were added. A version is a
// `sub:DocumentVersion` child, its one file a `sub:File` named `file`, and the upload sits under
// that as `uploadedFile` — fixed names, so the name the file arrived under is a property.
function uploadsOf(document: JsonNode): Upload[] {
  return Object.values(document).flatMap(value => {
    if (!isNode(value) || (value["sling:resourceType"] !== "sub/DocumentVersion"
      && value["jcr:primaryType"] !== "sub:DocumentVersion")) {
      return [];
    }
    const file = value.file;
    return isNode(file) && isNode(file.uploadedFile) ? [{ version: value, file }] : [];
  });
}

// Where reading the answers out of the uploaded documents got to. While it runs the page asks again
// every few seconds; when it stops without answers the person is told why, in the words the server
// chose.
function ExtractionProgress(
  { extraction, waiting, onRetry }: { extraction: ExtractionState; waiting: boolean; onRetry: () => void }
) {
  if (extraction.status === "running" && !waiting) {
    // Stopped asking, and the server still says it is running. Nothing more will arrive on its own, so
    // say so rather than spin: a spinner that never stops cannot be told from work still going on.
    return (
      <Alert severity="warning">
        The document is taking longer to read than expected. Reload the page to check again.
      </Alert>
    );
  }
  if (extraction.status === "running") {
    return (
      <Alert severity="info" icon={<CircularProgress size={20} />} role="status">
        Reading the uploaded document. Answers found in it will appear here when it is done.
      </Alert>
    );
  }
  if (extraction.status === "done") {
    return null;
  }
  // Only `failed` is left here: the daemon unreachable, the model refusing, none of it anything the
  // submitter did or can see, so asking again is worth offering.
  const again = <Button color="inherit" size="small" onClick={onRetry}>Try again</Button>;
  return (
    <Alert severity="warning" action={again}>
      {extraction.message ?? "The uploaded document could not be read, so nothing was filled in from it."}
    </Alert>
  );
}

// One attached document: what it is called and a download link for each version's upload.
function Attachment({ document, named }: { document: JsonNode; named: boolean }) {
  const requirement = isNode(document.fulfills) ? document.fulfills : undefined;
  const uploads = uploadsOf(document);
  // A reference is serialized with whatever the referenced node holds, and a requirement need not
  // carry a label. Worth saying only where the grouping does not already say it, and only where
  // there is something to say: `fulfills "undefined"` is worse than nothing at all.
  const fulfills = named && typeof requirement?.label === "string" ? requirement.label : undefined;
  const title = String(document.title ?? document["@name"]);
  // The title is the uploaded file's name unless somebody said otherwise, and the link below already
  // says that. Shown only when it says something the link does not, or when there is no link.
  const names = uploads.map(({ file }) => (typeof file.fileName === "string" ? file.fileName : ""));
  const heading = names.includes(title) ? undefined : title;
  return (
    <Box>
      {heading || fulfills
        ? (
          <Typography variant="subtitle2">
            {heading ?? ""}
            {fulfills ? `${heading ? " — " : ""}fulfills "${fulfills}"` : ""}
          </Typography>
        )
        : null}
      {document.description
        ? <Typography variant="description">{formatValue(document.description)}</Typography>
        : null}
      <Stack>
        {uploads.map(({ version, file }) => {
          const name = typeof file.fileName === "string" ? file.fileName : "uploadedFile";
          const number = String(version["@name"]).replace(/^v/, "");
          // The node is called uploadedFile whatever the file was called, so the download says the real name
          return (
            <Link key={String(version["@name"])} href={fileHref(`${String(version["@path"])}/file`, "uploadedFile")}
              download={name}>
              {uploads.length > 1 ? `${name} (version ${number})` : name}
            </Link>
          );
        })}
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
  const fulfilling = (requirement: FormRequirement) => documentsFulfilling(requirement, documents);
  // Anything whose requirement does not currently apply, is gone from the schema, or that never named
  // one: still somebody's evidence, so shown rather than silently dropped
  const claimed = new Set(requirements.flatMap(requirement =>
    fulfilling(requirement).map(document => document["@path"])));
  const unattributed = documents.filter(document => !claimed.has(document["@path"]));

  if (requirements.length === 0 && documents.length === 0) {
    return <Typography variant="placeholder">This request asks for no documents</Typography>;
  }

  return (
    <Stack spacing={2} divider={<Divider />}>
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
              : <Typography variant="placeholder">Nothing attached yet</Typography>}
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
    return <Typography variant="placeholder">This request needs no approvals</Typography>;
  }
  return (
    <Stack spacing={2} divider={<Divider />}>
      {requirements.map(requirement => (
        <Stack key={requirement.name} spacing={1}>
          <Typography variant="subtitle1">{requirement.label || requirement.name}</Typography>
          {requirement.description
            ? <Typography variant="description">{requirement.description}</Typography>
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
  // Kept apart from `error`, which takes the whole page down: a retry that was refused has not stopped
  // the submission from being shown, and the one thing worth saying about it belongs beside the banner
  // the button is on.
  const [retryError, setRetryError] = useState<string>();
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
    fetchForm(path, fetchUtil).then(
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
  }, [path, reloads, fetchUtil]);

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
        return readJson<JsonNode>(response);
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

  // While the documents are still being read, ask again in a little while: the answers land on the
  // submission from a background job, and nothing else on this page would notice them arriving.
  //
  // Counted, and given up on. A parse the daemon never answers for is failed by the server sweep, so
  // this stops on its own in the normal case; the count is for the case where nothing is answering,
  // where polling every four seconds for as long as somebody leaves the tab open helps nobody.
  // Counted per submission, and reset when the page turns to another one: the budget is what this
  // reading is worth waiting for, and carrying an exhausted count across would tell somebody opening
  // a second submission that its reading had already taken too long before it had taken any time at all.
  const extracting = form?.extraction?.status === "running";
  const [ polls, setPolls ] = useState(0);
  const [ polledFor, setPolledFor ] = useState(path);
  if (polledFor !== path) {
    // Adjusted while rendering rather than in an effect, which is React's own way of resetting state
    // when a prop changes: doing it in an effect renders once with the old count before correcting it.
    setPolledFor(path);
    setPolls(0);
  }
  useEffect(() => {
    if (!extracting || polls >= EXTRACTION_POLL_LIMIT) {
      return undefined;
    }
    const timer = setTimeout(() => {
      setPolls(current => current + 1);
      setReloads(current => current + 1);
    }, EXTRACTION_POLL_MS);
    return () => clearTimeout(timer);
  }, [extracting, reloads, polls]);

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
          blockedReason={whyBlocked(submission, form)}
          refreshToken={reloads}
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

  // Asking again starts the waiting over as well as the parse. Without resetting the count, a page that
  // had already given up on the last reading would show the new one as overdue the moment it began.
  const askAgain = () => {
    setRetryError(undefined);
    // From the daemon when a parse failed, from the model when the document was read but the answers
    // were not: sending a perfectly good document to the daemon again would fix nothing.
    void readAgain(path, form?.extraction?.retryable === true)
      .then(() => {
        setPolls(0);
        setReloads(current => current + 1);
      })
      // The refusal's own words: the engine says why it would not take this, and wrapping that in
      // "something went wrong" buries the one sentence worth reading.
      .catch((e: unknown) => setRetryError(messageOf(e)));
  };

  // Shown in both modes: whoever is filling the form in is the one waiting for the answers to arrive
  const progress = form?.extraction
    ? (
      <>
        <ExtractionProgress
          extraction={form.extraction}
          waiting={polls < EXTRACTION_POLL_LIMIT}
          onRetry={askAgain}
        />
        {retryError ? <Alert severity="error">{retryError}</Alert> : null}
      </>
    )
    : null;

  if (editing) {
    return (
      <Stack spacing={2}>
        {header}
        {progress}
        {/* Answering or attaching can be the thing that completes the request, and whether it is
            complete decides whether the step above offers to send it. Re-read here rather than
            worked out again: the save workflow already recorded it on the submission. */}
        <SubmissionEditor
          path={path}
          onChanged={() => setReloads(current => current + 1)}
          blockedReason={whyBlocked(submission, form)}
          // Reloaded, not navigated away from. The step offered inside the form is one the submitter
          // takes while filling it in - it sets the reading going - and the page they want next is the
          // one they are on. Only the page-level steps below make a submission read-only.
          onTaskCompleted={() => setReloads(current => current + 1)}
          // Re-read whenever the page does, which includes each poll while the reading runs: that is
          // how answers the model found appear without the submitter reloading the page
          refreshToken={reloads}
        />
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
  const documents = childrenOfType(submission, "sub/Document");
  const reviews = childrenOfType(submission, "sub/Review");

  return (
    <Stack spacing={2}>
      {header}
      {progress}
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
      {(form?.requirements ?? [])
        .filter(requirement => requirement.type === FORM_REQUIREMENT)
        .map(requirement => (
          <Section
            key={requirement.name}
            title={requirement.label || requirement.name}
            subtitle={requirement.description}
          >
            <FormItems items={requirement.items ?? []} level={0} />
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
          : <Typography variant="placeholder">No reviews yet</Typography>}
      </Section>
    </Stack>
  );
}

export default SubmissionView;
