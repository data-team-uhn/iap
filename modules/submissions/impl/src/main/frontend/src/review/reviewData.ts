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

import type { PassageKind } from "@iap/frontend-commons/pdfViewer/marksModel";

import type { Message } from "./reviewModel";

// PRETOTYPE. A submission as its review screen reads it: the answers and documents, which reviewer groups each
// question and document matters to, and everything written about them, as notes of five kinds.

type Node = Record<string, unknown>;

export type NoteKind = "evidence" | "aiIssue" | "issue" | "discussion" | "note";

export interface NoteKindInfo {
  kind: NoteKind;
  // What the filter calls it
  label: string;
  // What one of them is called on its card
  single: string;
  mark: PassageKind;
}

export const NOTE_KINDS: NoteKindInfo[] = [
  { kind: "evidence", label: "Extracted answers", single: "Extracted answer", mark: "evidence" },
  { kind: "aiIssue", label: "AI pre-review", single: "AI pre-review", mark: "assistant" },
  { kind: "issue", label: "Issues for the study team", single: "Issue", mark: "issue" },
  { kind: "discussion", label: "Reviewer discussion", single: "Discussion", mark: "discussion" },
  { kind: "note", label: "My notes", single: "My note", mark: "annotation" },
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
  // An AI issue's risk
  topic?: string;
  suggestion?: string;
  // An extracted answer's question, by path, what was read, and how
  question?: string;
  extracted?: string;
  reasoning?: string;
  confidence?: number;
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
  // The schema part an answer or document UUID stands for
  partOf: Map<string, string>;
}

const isNode = (value: unknown): value is Node => typeof value === "object" && value !== null && !Array.isArray(value);
const text = (value: unknown): string => typeof value === "string" ? value : "";
const uuidOf = (value: unknown): string => isNode(value) ? text(value["jcr:uuid"]) : text(value);
const pathOf = (value: unknown): string => isNode(value) ? text(value["@path"]) : text(value);

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

function replies(node: Node): Message[] {
  return childrenOf(node, "sub:Reply").map(reply => ({
    path: text(reply["@path"]), text: text(reply.text), author: text(reply.author),
    created: text(reply["jcr:created"]), replies: [],
  }));
}

function severityOf(node: Node): string | undefined {
  const tags = Array.isArray(node.tags) ? node.tags.map(String) : [];
  return tags.find(tag => (SEVERITIES as readonly string[]).includes(tag));
}

function comment(node: Node, kind: NoteKind, me: string, topic?: string): ReviewNote {
  const author = text(node.author);
  return {
    id: text(node["@path"]),
    kind,
    text: text(node.text),
    author,
    created: text(node["jcr:created"]),
    mine: author === me,
    subjects: Array.isArray(node.subjects) ? node.subjects.map(uuidOf).filter(Boolean) : [],
    passage: passageOf(node),
    replies: replies(node),
    severity: severityOf(node),
    topic,
    suggestion: text(node.suggestion) || undefined,
  };
}

export function parseReview(submission: Node, schema: string, me: string): ReviewData {
  const answers = new Map<string, ReviewedAnswer>();
  const documents = new Map<string, ReviewedDocument>();
  const partOf = new Map<string, string>();
  const notes: ReviewNote[] = [];
  for (const node of Object.values(submission).filter(isNode)) {
    const type = node["sling:resourceType"];
    if (type === "sub/Answer") {
      const question = relative(pathOf(node.question), schema);
      const values = (Array.isArray(node.value) ? node.value : [ node.value ])
        .filter(value => value !== undefined && value !== null).map(value => text(value) || JSON.stringify(value));
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
        });
      }
    } else if (type === "sub/Document") {
      const requirement = relative(pathOf(node.fulfills), schema);
      const version = childrenOf(node, "sub:DocumentVersion").at(-1);
      const file = version && isNode(version.file) ? version.file : undefined;
      if (version && file) {
        const uuid = text(node["jcr:uuid"]);
        documents.set(requirement, { uuid, version: text(version["jcr:uuid"]), title: text(node.title),
          url: `${text(file["@path"])}/uploadedFile` });
        partOf.set(uuid, requirement);
        partOf.set(text(version["jcr:uuid"]), requirement);
      }
    } else if (type === "sub/Screening") {
      for (const assessment of childrenOf(node, "sub:Assessment")) {
        const topic = isNode(assessment.topic) ? text(assessment.topic.text) : undefined;
        notes.push(...childrenOf(assessment, "sub:Comment").map(one => comment(one, "aiIssue", me, topic)));
      }
    } else if (type === "sub/Review") {
      notes.push(...childrenOf(node, "sub:Comment").map(one => comment(one, "issue", me)));
    } else if (type === "sub/Discussion") {
      notes.push(...childrenOf(node, "sub:Comment").map(one => comment(one, "discussion", me)));
    } else if (type === "irv/Notes" && node.owner === me) {
      notes.push(...childrenOf(node, "sub:Comment").map(one => comment(one, "note", me)));
    }
  }
  return { answers, documents, notes, partOf };
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

// Whether a question or document is the reviewer's to look at: one of their groups asked for it, or nobody in
// particular did, as with the title or the PI, which every reviewer reads
export function isMine(part: string, groups: Map<string, ReviewGroup[]>, myGroups: string[]): boolean {
  const asking = groups.get(part);
  return !asking || asking.some(one => myGroups.includes(one.group));
}

// The notes about an answer or document, whether they point at it or quote it
export function notesAbout(notes: ReviewNote[], uuids: string[]): ReviewNote[] {
  return notes.filter(note => note.subjects.some(subject => uuids.includes(subject))
    || (note.passage?.source !== undefined && uuids.includes(note.passage.source)));
}

// A short label for reviewer groups on a chip
export function groupShortName(label: string): string {
  return ({ "Research ethics": "REB", "Digital review": "Digital", "Department head": "Dept. head",
    "Research quality": "RQI", "Princess Margaret data": "CRDAC" } as Record<string, string>)[label] ?? label;
}
