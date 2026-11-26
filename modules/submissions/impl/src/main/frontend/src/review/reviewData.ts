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

import type { PassageKind } from "@iap/documents-pdf-viewer/marksModel";

import type { Message } from "./reviewModel";

// PRETOTYPE. A submission as its review screen reads it: the answers and documents, which reviewer groups each
// question and document matters to, and everything written about them, as notes of five kinds.

type Node = Record<string, unknown>;

// An issue is the reviewer's own, what their letter holds; another reviewer's, of their group or another, is apart
export type NoteKind = "evidence" | "contradiction" | "aiIssue" | "issue" | "otherIssue" | "discussion" | "note";

export interface NoteKindInfo {
  kind: NoteKind;
  // What the filter calls it
  label: string;
  // What one of them is called on its card
  single: string;
  // What it is called where the reviewer picks what to write, all of it being theirs
  choice: string;
  mark: PassageKind;
}

export const NOTE_KINDS: NoteKindInfo[] = [
  // PRETOTYPE: on the review screen, what the AI read in the protocol for an answer shows as the passage linked to the
  // answer, when the submitter kept it, or as a contradiction, when the protocol clearly says otherwise; these are
  // always shown
  { kind: "evidence", label: "Linked answers", single: "Linked answer", choice: "Linked answer", mark: "evidence" },
  { kind: "contradiction", label: "Contradictions", single: "Contradiction", choice: "Contradiction",
    mark: "warning" },
  { kind: "aiIssue", label: "AI pre-review", single: "AI pre-review", choice: "AI pre-review", mark: "assistant" },
  { kind: "issue", label: "Your issues", single: "Issue", choice: "Issue", mark: "issue" },
  { kind: "discussion", label: "Reviewer discussion", single: "Discussion", choice: "Discussion", mark: "discussion" },
  { kind: "note", label: "Notes to self", single: "Note to self", choice: "Note to self", mark: "annotation" },
  { kind: "otherIssue", label: "Others' issues", single: "Issue", choice: "Issue", mark: "otherIssue" },
];

export function kindInfo(kind: NoteKind): NoteKindInfo {
  return NOTE_KINDS.find(info => info.kind === kind) ?? NOTE_KINDS[0];
}

export const SEVERITIES = [ "critical", "major", "medium", "minor", "suggestion" ] as const;

export interface Passage {
  // The document revision quoted, by UUID
  source?: string;
  quote: string;
  prefix?: string;
  suffix?: string;
  page?: number;
}

export interface ReviewNote {
  id: string;
  kind: NoteKind;
  text: string;
  author: string;
  created: string;
  mine: boolean;
  // The answers and documents it is about, by UUID
  subjects: string[];
  passage?: Passage;
  replies: Message[];
  severity?: string;
  // An AI comment the reviewer settled: the issue they made of it, by path, or "dismissed"
  settled?: string;
  // A note raised as an issue: the issues made of it, by path, with who raised each, by principal name, and whether
  // the reader did
  raisedAs?: { id: string; author: string }[];
  raisedByMe?: boolean;
  // An issue made of a note: that note, by path, and what kind it is, once it is found among the notes
  from?: string;
  fromKind?: NoteKind;
  // An open AI comment: the issues its answers already have, made of other notes, with who raised each
  issuesOnAnswer?: { id: string; author: string }[];
  // PRETOTYPE: who marked it resolved, a discussion entry, an issue or a note to self; whether the reader dismissed it,
  // an AI comment or a contradiction; and so whether it is done with, shown only when the reader asks
  resolvedBy?: string;
  dismissed?: boolean;
  done?: "dismissed" | "resolved" | "addressed";
  // Another reviewer's issue: the reviewer group they raised it for, by principal name
  group?: string;
  // The risk an AI issue was raised under, or a reviewer's issue is filed under: its name, and its path
  topic?: string;
  topicPath?: string;
  suggestion?: string;
  // An extracted answer's question, by path, what was read, and how
  question?: string;
  extracted?: string;
  reasoning?: string;
  confidence?: number;
  // How far, in percent, the submitter's answer ended up from the extracted one, once they kept or changed it
  distance?: number;
  // PRETOTYPE: written before the submission was last sent back, about an answer that changed since
  answerChanged?: boolean;
}

export interface ReviewedAnswer {
  path: string;
  uuid: string;
  values: string[];
}

export interface ReviewedDocument {
  uuid: string;
  version: string;
  title: string;
  url: string;
  // PRETOTYPE: every version, in the order they were uploaded, the last being the one above
  versions: DocumentVersionInfo[];
}

export interface DocumentVersionInfo {
  uuid: string;
  // Its place among the document's versions, from 1
  number: number;
  created: string;
  url: string;
}

export interface ReviewGroup {
  // The approval requirement, by name, and what it is called
  name: string;
  label: string;
  group: string;
}

export interface ReviewData {
  answers: Map<string, ReviewedAnswer>;
  documents: Map<string, ReviewedDocument>;
  notes: ReviewNote[];
  // The AI pre-review's comments this reviewer has made into issues or dismissed, kept apart from the notes shown
  settledAi: ReviewNote[];
  // The schema part an answer or document UUID stands for
  partOf: Map<string, string>;
  // The AI pre-review: its overall read, and its read of each risk, by the risk's path
  aiSummary?: string;
  assessments: Map<string, string>;
  // The risks the reviewer has marked as considered, by path
  considered: Set<string>;
  // PRETOTYPE: each answer as it was when the submission was last sent back, by question path, when it was and its
  // changes are shown
  before?: Map<string, string[]>;
  // The reviewer's latest decision, and the letters that came with their decisions, latest last
  decided?: Decided;
  letters: Letter[];
}

export interface Decided {
  outcome: string;
  at: string;
}

export interface Letter {
  outcome: string;
  at: string;
  text: string;
}

const DECISIONS = [ "approved", "changes-requested", "rejected" ];

const isNode = (value: unknown): value is Node => typeof value === "object" && value !== null && !Array.isArray(value);
const text = (value: unknown): string => typeof value === "string" ? value : "";
const uuidOf = (value: unknown): string => isNode(value) ? text(value["jcr:uuid"]) : text(value);
const pathOf = (value: unknown): string => isNode(value) ? text(value["@path"]) : text(value);

// An answer's values as text, a boolean or a number written as JSON
function answerValues(node: Node): string[] {
  return (Array.isArray(node.value) ? node.value : [ node.value ])
    .filter(value => value !== undefined && value !== null).map(value => text(value) || JSON.stringify(value));
}

function childrenOf(node: Node, type: string): Node[] {
  return Object.values(node).filter((child): child is Node => isNode(child) && child["jcr:primaryType"] === type)
    .sort((a, b) => Date.parse(text(a["jcr:created"])) - Date.parse(text(b["jcr:created"])));
}

function relative(path: string, schema: string): string {
  return path.startsWith(`${schema}/`) ? path.slice(schema.length + 1) : path;
}

function passageOf(node: Node): Passage | undefined {
  const context = childrenOf(node, "sub:Context").at(0);
  if (!context || !text(context.quote)) {
    return undefined;
  }
  return {
    source: uuidOf(context.source) || undefined,
    quote: text(context.quote),
    prefix: text(context.prefix) || undefined,
    suffix: text(context.suffix) || undefined,
    page: typeof context.page === "number" ? context.page : undefined,
  };
}

function replies(node: Node, me: string): Message[] {
  return childrenOf(node, "sub:Reply").map(reply => ({
    path: text(reply["@path"]), text: text(reply.text), author: text(reply.author),
    created: text(reply["jcr:created"]), replies: [], mine: reply.author === me,
  }));
}

function severityOf(node: Node): string | undefined {
  const tags = Array.isArray(node.tags) ? node.tags.map(String) : [];
  return tags.find(tag => (SEVERITIES as readonly string[]).includes(tag));
}

// A risk a reviewer group weighs, under the group's approval: the schema parts it draws on, relative to the schema
export interface Topic {
  path: string;
  label: string;
  description?: string;
  approval: string;
  sources: string[];
}

function comment(node: Node, kind: NoteKind, me: string, topic?: Node, topics?: Map<string, Topic>): ReviewNote {
  const author = text(node.author);
  const topicPath = topic ? text(topic["@path"]) : text(node.topic);
  return {
    id: text(node["@path"]),
    kind,
    text: text(node.text),
    author,
    created: text(node["jcr:created"]),
    mine: author === me,
    subjects: Array.isArray(node.subjects) ? node.subjects.map(uuidOf).filter(Boolean) : [],
    passage: passageOf(node),
    replies: replies(node, me),
    severity: severityOf(node),
    topic: (topic ? text(topic.text) : topics?.get(topicPath)?.label ?? "") || undefined,
    topicPath: topicPath || undefined,
    suggestion: text(node.suggestion) || undefined,
    resolvedBy: text(node.resolvedBy) || undefined,
    from: text(node.from) || undefined,
  };
}

export function parseReview(submission: Node, schema: string, me: string, topics?: Map<string, Topic>): ReviewData {
  const answers = new Map<string, ReviewedAnswer>();
  const documents = new Map<string, ReviewedDocument>();
  const partOf = new Map<string, string>();
  const notes: ReviewNote[] = [];
  // The AI pre-review comments this reviewer made into issues or dismissed, which they no longer need to see
  // How each settled one was: the issue made of it, by path, or "dismissed"
  const settled = new Map<string, string>();
  // The issues made of each note, by the note's path, whoever's review they are in, with who raised each
  const raised = new Map<string, { id: string; author: string }[]>();
  let aiSummary: string | undefined;
  const assessments = new Map<string, string>();
  let considered = new Set<string>();
  let decided: Decided | undefined;
  const letters: Letter[] = [];
  for (const node of Object.values(submission).filter(isNode)) {
    const type = node["sling:resourceType"];
    if (type === "sub/Answer") {
      const question = relative(pathOf(node.question), schema);
      const values = answerValues(node);
      const uuid = text(node["jcr:uuid"]);
      answers.set(question, { path: text(node["@path"]), uuid, values });
      partOf.set(uuid, question);
      const extraction = childrenOf(node, "sub:Extraction").at(-1);
      if (extraction) {
        notes.push({
          id: text(extraction["@path"]), kind: "evidence", text: text(extraction.reasoning), author: "iap-assistant",
          created: text(extraction["jcr:created"]), mine: false, subjects: [ uuid ], passage: passageOf(extraction),
          replies: [], question, extracted: text(extraction.extractedAnswer),
          reasoning: text(extraction.reasoning), confidence: typeof extraction.confidence === "number"
            ? extraction.confidence : undefined,
          distance: typeof extraction.percentageDistance === "number" ? extraction.percentageDistance : undefined,
          dismissed: Array.isArray(extraction.dismissedBy) && extraction.dismissedBy.includes(me),
        });
      }
    } else if (type === "sub/Document") {
      const requirement = relative(pathOf(node.fulfills), schema);
      // PRETOTYPE: each version, a note quoting an older one being about the document all the same
      const versions = childrenOf(node, "sub:DocumentVersion").flatMap((one, index) => isNode(one.file) ? [ {
        uuid: text(one["jcr:uuid"]), number: index + 1, created: text(one["jcr:created"]),
        url: `${text(one.file["@path"])}/uploadedFile` } ] : []);
      const latest = versions.at(-1);
      if (latest) {
        const uuid = text(node["jcr:uuid"]);
        documents.set(requirement, { uuid, version: latest.uuid, title: text(node.title), url: latest.url, versions });
        partOf.set(uuid, requirement);
        versions.forEach(one => partOf.set(one.uuid, requirement));
      }
    } else if (type === "sub/Screening") {
      for (const assessment of childrenOf(node, "sub:Assessment")) {
        const topic = isNode(assessment.topic) ? assessment.topic : undefined;
        if (topic && text(assessment.summary)) {
          assessments.set(text(topic["@path"]), text(assessment.summary));
        }
        const comments = childrenOf(assessment, "sub:Comment");
        comments.filter(one => Array.isArray(one.dismissedBy) && one.dismissedBy.includes(me))
          .forEach(one => settled.set(text(one["@path"]), "dismissed"));
        notes.push(...comments.map(one => comment(one, "aiIssue", me, topic)));
      }
      aiSummary = text(node.summary) || undefined;
    } else if (type === "sub/Review") {
      if (node.reviewer === me && Array.isArray(node.considered)) {
        considered = new Set(node.considered.map(String));
      }
      // What the reviewer wrote with a decision is their letter, not an issue
      const comments = childrenOf(node, "sub:Comment").filter(one => !text(one.decision));
      if (node.reviewer === me) {
        const outcome = (Array.isArray(node.tags) ? node.tags.map(String) : []).find(tag => DECISIONS.includes(tag));
        const at = text(node.decidedAt) || text(node["jcr:lastModified"]);
        if (outcome && (!decided || Date.parse(at) > Date.parse(decided.at))) {
          decided = { outcome, at };
        }
        letters.push(...childrenOf(node, "sub:Comment").filter(one => text(one.decision))
          .map(one => ({ outcome: text(one.decision), at: text(one["jcr:created"]), text: text(one.text) })));
      }
      comments.filter(one => one.author === me && text(one.from))
        .forEach(one => settled.set(text(one.from), text(one["@path"])));
      comments.filter(one => text(one.from))
        .forEach(one => raised.set(text(one.from), [ ...raised.get(text(one.from)) ?? [],
          { id: text(one["@path"]), author: text(one.author) } ]));
      const group = text(node.group) || undefined;
      notes.push(...comments.map(one => ({ ...comment(one, one.author === me ? "issue" : "otherIssue", me, undefined,
        topics), group })));
    } else if (type === "sub/Discussion") {
      notes.push(...childrenOf(node, "sub:Comment").map(one => comment(one, "discussion", me)));
    } else if (type === "irv/Notes" && node.owner === me) {
      notes.push(...childrenOf(node, "sub:Comment").map(one => comment(one, "note", me)));
    }
  }
  // Only the AI's comments are set aside once settled; a discussion entry made into an issue stays. Each says which
  // issues were made of it.
  const withRaised = (note: ReviewNote): ReviewNote => raised.has(note.id)
    ? { ...note, raisedAs: raised.get(note.id), raisedByMe: raised.get(note.id)?.some(one => one.author === me) }
    : note;
  const isSettled = (note: ReviewNote) => note.kind === "aiIssue" && settled.has(note.id);
  const settledAi = notes.filter(isSettled).map(note => withRaised({ ...note, settled: settled.get(note.id) }));
  const shown = notes.filter(note => !isSettled(note)).map(withRaised);
  return { answers, documents, notes: shown, settledAi, partOf, aiSummary,
    assessments, considered, decided, letters: letters.sort((a, b) => Date.parse(a.at) - Date.parse(b.at)) };
}

// The risks each approval lists, by path
export function parseTopics(approvals: Node[], schema: string): Map<string, Topic> {
  const topics = new Map<string, Topic>();
  for (const approval of approvals) {
    for (const [ name, topic ] of Object.entries(approval)) {
      if (!isNode(topic) || topic["jcr:primaryType"] !== "sch:Topic") {
        continue;
      }
      const path = text(topic["@path"]) || `${text(approval["@path"])}/${name}`;
      topics.set(path, { path, label: text(topic.text), description: text(topic.description) || undefined,
        approval: text(approval["@name"]),
        sources: (Array.isArray(topic.sources) ? topic.sources : []).map(source => relative(pathOf(source), schema)) });
    }
  }
  return topics;
}

// Which reviewer groups each question and document matters to, from the risks each approval lists
export function parseGroups(approvals: Node[], schema: string): Map<string, ReviewGroup[]> {
  const groups = new Map<string, ReviewGroup[]>();
  for (const approval of approvals) {
    const group: ReviewGroup = { name: text(approval["@name"]), label: text(approval.label),
      group: text(approval.approverGroup) };
    for (const topic of Object.values(approval).filter(isNode)) {
      const sources = Array.isArray(topic.sources) ? topic.sources : [];
      for (const source of sources) {
        const part = relative(pathOf(source), schema);
        const known = groups.get(part) ?? [];
        if (!known.some(one => one.name === group.name)) {
          groups.set(part, [ ...known, group ]);
        }
      }
    }
  }
  return groups;
}

// Whether a question or document is the reviewer's to look at: one of their groups' risks draws on it. What every
// reviewer reads, such as the title or the PI, the schema says apart, as a requirement for everyone.
export function isMine(part: string, groups: Map<string, ReviewGroup[]>, myGroups: string[]): boolean {
  return groups.get(part)?.some(one => myGroups.includes(one.group)) ?? false;
}

// The notes about an answer or document, whether they point at it or quote it
export function notesAbout(notes: ReviewNote[], uuids: string[]): ReviewNote[] {
  return notes.filter(note => note.subjects.some(subject => uuids.includes(subject))
    || (note.passage?.source !== undefined && uuids.includes(note.passage.source)));
}

// A short label for reviewer groups on a chip
// An issue for the study team written from an AI pre-review note: about the same answers and documents, on the
// same passage, as the reviewer worded it
export function issueFrom(note: ReviewNote, text: string, severity?: string, topic?: string)
  : Record<string, string | string[]> {
  const passage = note.passage;
  return {
    // A note to self gives way to the issue made of it, so the issue does not point back at it
    kind: "issue", text, ...note.kind === "note" ? {} : { from: note.id }, subjects: note.subjects,
    ...severity ? { severity } : {},
    ...topic ? { topic } : {},
    ...passage ? {
      quote: passage.quote,
      ...passage.source ? { source: passage.source } : {},
      ...passage.prefix ? { prefix: passage.prefix } : {},
      ...passage.suffix ? { suffix: passage.suffix } : {},
      ...passage.page !== undefined ? { page: String(passage.page) } : {},
    } : {},
  };
}

export function groupShortName(label: string): string {
  return ({ "Research ethics": "REB", "Digital review": "Digital", "Department head": "Dept. head",
    "Research quality": "RQI", "Princess Margaret data": "CRDAC" } as Record<string, string>)[label] ?? label;
}

// Makes an issue of a note: of an AI comment, which it settles; of a discussion entry, which stays and says so; or
// of a note to self, which it replaces
export async function raiseAsIssue(write: (fields: Record<string, string | string[]>) => Promise<void>,
  note: ReviewNote, text: string, severity?: string, topic?: string): Promise<void> {
  await write(issueFrom(note, text, severity, topic));
  if (note.kind === "note") {
    await write({ remove: note.id });
  }
}

// PRETOTYPE. Whether each note is done with, for the reader: an AI comment or a contradiction they dismissed, an AI
// comment they made into an issue, which then addresses it, as does a contradiction or a discussion entry anyone made
// into one, and anything marked resolved. The AI comments they settled come back among the notes, done, to be shown
// when asked.
export function withDone(data: ReviewData): ReviewData {
  const done = (note: ReviewNote): ReviewNote["done"] => {
    if (note.kind === "aiIssue") {
      return note.settled === undefined ? undefined : note.settled === "dismissed" ? "dismissed" : "addressed";
    }
    if (note.dismissed) {
      return "dismissed";
    }
    if ((note.kind === "contradiction" || note.kind === "discussion") && (note.raisedAs?.length ?? 0) > 0) {
      return "addressed";
    }
    return note.resolvedBy ? "resolved" : undefined;
  };
  // An issue says what kind of note it was made of, as that note now shows
  const all = [ ...data.notes, ...data.settledAi ];
  const kinds = new Map(all.map(note => [ note.id, note.kind ]));
  // An AI comment still open says which issues its answers already have, made of something else, as of a
  // contradiction saying the same
  const answers = new Set([ ...data.answers.values() ].map(answer => answer.uuid));
  const issues = all.filter(note => note.kind === "issue" || note.kind === "otherIssue");
  const issuesOn = (note: ReviewNote) => {
    const on = note.subjects.filter(subject => answers.has(subject));
    const found = issues.filter(issue => issue.from !== note.id && issue.subjects.some(one => on.includes(one)))
      .map(issue => ({ id: issue.id, author: issue.author }));
    return found.length > 0 ? found : undefined;
  };
  return { ...data, notes: all.map(note => {
    const isDone = done(note);
    return { ...note, done: isDone, fromKind: note.from ? kinds.get(note.from) : undefined,
      issuesOnAnswer: note.kind === "aiIssue" && !isDone ? issuesOn(note) : undefined };
  }) };
}

// PRETOTYPE. The copy of the submission kept when one of the reader's groups last sent it back for changes, by the
// path of its version: the latest decided of their groups' reviews that names one; failing that, the latest kept
// for any group, what changed since the submission was last sent back being worth reading all the same.
export function sentBackCopy(submission: Node, myGroups: string[]): { path: string; at: string } | undefined {
  const reviews = childrenOf(submission, "sub:Review").filter(review => text(review.sentBackCopy))
    .sort((a, b) => Date.parse(text(b.decidedAt)) - Date.parse(text(a.decidedAt)));
  const latest = reviews.find(review => myGroups.includes(text(review.group))) ?? reviews.at(0);
  return latest ? { path: text(latest.sentBackCopy), at: text(latest.decidedAt) } : undefined;
}

// PRETOTYPE. Whether an answer is not what it was, the order of several values aside; every change to an answer
// counts, minor changes being a document's alone
export function isChanged(was: string[], now: string[]): boolean {
  const normal = (values: string[]) => [ ...values ].map(value => value.trim()).sort().join("\n");
  return normal(was) !== normal(now);
}

// PRETOTYPE. The questions whose answers changed since the copy, by path
export function changedAnswers(data: ReviewData): Set<string> {
  const before = data.before;
  if (!before) {
    return new Set();
  }
  const paths = new Set([ ...before.keys(), ...data.answers.keys() ]);
  return new Set([ ...paths ].filter(path => isChanged(before.get(path) ?? [], data.answers.get(path)?.values ?? [])));
}

// PRETOTYPE. What was written before the submission was last sent back says when an answer it is about changed since;
// linked answers and contradictions are read against the answers as they are, so they say nothing of it
export function withAnswerChanges(data: ReviewData, sentBackAt: string | undefined): ReviewData {
  const changed = changedAnswers(data);
  if (changed.size === 0 || !sentBackAt) {
    return data;
  }
  const at = Date.parse(sentBackAt);
  return { ...data, notes: data.notes.map(note => note.kind !== "evidence" && note.kind !== "contradiction"
    && Date.parse(note.created) <= at
    && note.subjects.some(subject => changed.has(data.partOf.get(subject) ?? "")) ? { ...note, answerChanged: true }
    : note) };
}

// PRETOTYPE. Each answer in a copy of the submission, by its question's path relative to the schema version
export function answersIn(copy: Node, schema: string): Map<string, string[]> {
  return new Map(childrenOf(copy, "sub:Answer").map(answer => [ relative(pathOf(answer.question), schema),
    answerValues(answer) ]));
}

// PRETOTYPE. What a document is compared with on the review screen: the version it had in the copy kept when the
// reader's group sent the submission back, or else the one before the latest; nothing when that is the latest.
export function comparisonFor(document: ReviewedDocument, copy: Node | undefined)
  : { url: string; label: string; current: string } | undefined {
  const inCopy = copy && childrenOf(copy, "sub:Document").find(one => text(one["jcr:uuid"]) === document.uuid);
  const then = inCopy ? text(childrenOf(inCopy, "sub:DocumentVersion").at(-1)?.["jcr:uuid"]) : undefined;
  const older = then !== undefined ? document.versions.find(one => one.uuid === then)
    : document.versions.at(-2);
  const latest = document.versions.at(-1);
  if (!older || !latest || older.uuid === document.version) {
    return undefined;
  }
  return { url: older.url, label: `Version of ${shortDate(older.created)}`,
    current: `Version of ${shortDate(latest.created)}` };
}

// PRETOTYPE. A day as versions and rounds are named: "Nov 22", with the year when it is not this one
export function shortDate(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  return date.toLocaleDateString(undefined, { month: "short", day: "numeric",
    ...date.getFullYear() === new Date().getFullYear() ? {} : { year: "numeric" } });
}
