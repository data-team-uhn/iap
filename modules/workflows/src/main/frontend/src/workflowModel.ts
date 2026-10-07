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

// What the workflow screens know about the repository: where workflows live, how a version's
// lifecycle reads, and how one workflow's versions are listed. No React, no fetch.

// The workflow definitions' canonical home; others may exist (the platform's own under
// /SystemWorkflows, another location's mirrored locally) but are discovered rather than listed here —
// this one is only the entry point discovery is asked through, and always exists.
export const WORKFLOWS_ROOT = "/Workflows";

// Where a workflow version stands is its `lifecycle` tag: authored as a `draft`, optionally put on
// `trial`, made `active` to run, and `retired` once a later version supersedes it or it is withdrawn —
// a retired version's own running instances carry on, but no new ones start from it until it is
// activated again. What may be done to a version next is what the server offers on it (see `offers`),
// so these are only read to describe it.
export const ACTIVE_TAG = "active";
export const RETIRED_TAG = "retired";

// A node parsed from the repository's JSON serialization: a known primary type, everything else
// read defensively.
export type JcrNode = {
  "jcr:primaryType"?: string;
} & Record<string, unknown>;

// One homepage workflows are stored in, as the discovery endpoint reports it.
export interface WorkflowHomepage {
  path: string;
  title: string;
}

// One homepage and how many workflows it holds, as a summary displays it — capped at a lower bound
// once the server is far enough past the requested page, rather than counting a very large collection
// in full just for a widget.
export interface WorkflowHomepageCount extends WorkflowHomepage {
  // Absent when the count could not be read: that this homepage exists is worth reporting on its
  // own, and is known independently of how many workflows are in it
  count?: number;
  atLeast: boolean;
}

// One version of a workflow definition, flattened for listing. The diagram itself is deliberately
// absent: it is an nt:file child of the version node, fetched on its own path only when something
// is about to render it.
export interface WorkflowVersionSummary {
  name: string;
  path: string;
  version: string;
  description: string;
  // The tags it carries itself, its lifecycle tag among them
  tags: string[];
  lastModified: string;
  // What the current user may do to it, as the events the server offers on it
  "@events": string[];
}

// One workflow definition with the versions stored under it, as its own page displays it.
export interface WorkflowSummary {
  path: string;
  name: string;
  title: string;
  // Whether new instances may be created from this workflow — derived from whether one of its
  // versions is active, rather than stored separately, so the two can never disagree.
  active: boolean;
  // Whether it has been taken out of use: a version is retired and none is active. A workflow that has
  // only ever had drafts and trials is neither active nor retired.
  retired: boolean;
  created: string;
  lastModified: string;
  // What the current user may do to the workflow itself, as the events the server offers on it
  "@events": string[];
  versions: WorkflowVersionSummary[];
}

function isNode(value: unknown): value is JcrNode {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

// The versions stored under a serialized workflow definition, in the repository's own order.
function parseVersions(definitionPath: string, definition: JcrNode): WorkflowVersionSummary[] {
  return Object.entries(definition)
    .filter(([, value]) => isNode(value) && value["jcr:primaryType"] === "wf:WorkflowVersion")
    .map(([name, value]) => {
      const version = value as JcrNode;
      return {
        name,
        path: `${definitionPath}/${name}`,
        version: text(version.version),
        description: text(version.description),
        tags: strings(version.tags),
        lastModified: text(version["jcr:lastModified"]),
        "@events": strings(version["@events"]),
      };
    });
}

// Reads a workflow from its definition, as served with its versions and the events offered on each.
export function workflowFrom(path: string, definition: JcrNode): WorkflowSummary {
  const versions = parseVersions(path, definition);
  return {
    path,
    name: path.slice(path.lastIndexOf("/") + 1),
    title: text(definition.title) || path.slice(path.lastIndexOf("/") + 1),
    active: versions.some(version => version.tags.includes(ACTIVE_TAG)),
    retired: versions.some(version => version.tags.includes(RETIRED_TAG))
      && !versions.some(version => version.tags.includes(ACTIVE_TAG)),
    created: text(definition["jcr:created"]),
    lastModified: text(definition["jcr:lastModified"]),
    "@events": strings(definition["@events"]),
    versions,
  };
}

// The homepages a discovery answer names, each one that has a path, in the order given.
export function homepagesFrom(answer: JcrNode): WorkflowHomepage[] {
  return (Array.isArray(answer.homepages) ? answer.homepages : [])
    .filter(isNode)
    .map(homepage => ({ path: text(homepage.path), title: text(homepage.title) }))
    .filter(homepage => homepage.path !== "");
}

// The diagram is an nt:file child of the version node rather than a property, so listing the versions
// no longer drags every diagram along. The `.xml` extension matters: Sling types a served file from
// its name, so without it the diagram would be served as an untyped binary.
export const BPMN_FILE = "bpmn.xml";

// Named as a multipart payload key rather than a Sling POST path with a type hint, because this is an
// event handed to the workflow engine, not a write — the handler looks up the key, and decides for
// itself where the diagram lands in the repository.
export function bpmnUpload(xml: string, body: FormData = new FormData()): FormData {
  body.set(BPMN_FILE, new File([xml], BPMN_FILE, { type: "application/xml" }));
  return body;
}

// What the canvas is handed for a version with no diagram saved. Importing it, rather than calling
// clear(), matters: clear() removes only the drawing and keeps the previous version's definitions, which
// a save would then export as this version's.
export const EMPTY_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="Definitions_empty" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Process_empty" isExecutable="false" />
  <bpmndi:BPMNDiagram id="BPMNDiagram_empty">
    <bpmndi:BPMNPlane id="BPMNPlane_empty" bpmnElement="Process_empty" />
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

// The diagram a brand-new workflow version starts from — a start event, one user task, an end event —
// so the editor opens on something rather than an empty canvas.
export const STARTING_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" id="Definitions_07212ml" targetNamespace="http://bpmn.io/schema/bpmn" exporter="bpmn-js (https://demo.bpmn.io)" exporterVersion="18.16.0">
  <bpmn:process id="Process_1ajiizs" isExecutable="false">
    <bpmn:sequenceFlow id="Flow_1bghbvl" sourceRef="StartEvent_0gcwblc" targetRef="Activity_0waxs0q" />
    <bpmn:sequenceFlow id="Flow_1cyttg7" sourceRef="Activity_0waxs0q" targetRef="Event_1q4m3yf" />
    <bpmn:startEvent id="StartEvent_0gcwblc">
      <bpmn:outgoing>Flow_1bghbvl</bpmn:outgoing>
      <bpmn:messageEventDefinition id="MessageEventDefinition_0s9hvhs" />
    </bpmn:startEvent>
    <bpmn:userTask id="Activity_0waxs0q">
      <bpmn:incoming>Flow_1bghbvl</bpmn:incoming>
      <bpmn:outgoing>Flow_1cyttg7</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:endEvent id="Event_1q4m3yf">
      <bpmn:incoming>Flow_1cyttg7</bpmn:incoming>
      <bpmn:messageEventDefinition id="MessageEventDefinition_06fhigp" />
    </bpmn:endEvent>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1ajiizs">
      <bpmndi:BPMNShape id="Event_15qreer_di" bpmnElement="StartEvent_0gcwblc">
        <dc:Bounds x="152" y="102" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Activity_10t3nsr_di" bpmnElement="Activity_0waxs0q">
        <dc:Bounds x="240" y="80" width="100" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Event_07olk38_di" bpmnElement="Event_1q4m3yf">
        <dc:Bounds x="392" y="102" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="Flow_1bghbvl_di" bpmnElement="Flow_1bghbvl">
        <di:waypoint x="188" y="120" />
        <di:waypoint x="240" y="120" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_1cyttg7_di" bpmnElement="Flow_1cyttg7">
        <di:waypoint x="340" y="120" />
        <di:waypoint x="392" y="120" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

// Where a workflow, a version, or a version's editor lives in the console — the repository path rides
// along in the URL, letting one page serve any homepage's workflows, e.g.
// /admin/workflows/Workflows/review or /admin/workflows/SystemWorkflows/newEntity/v1.
export const ADMIN_ROOT = "/admin/workflows";

// The page a console URL opens on top of a repository path — only the editor names itself, since
// viewing a workflow or version is just what its own path means. It is a suffix rather than a path
// segment because viewing and editing a version are the same thing seen two ways, not one nested in
// the other: a segment would have made the breadcrumb trail show the editor as a page below the
// viewer, which it isn't, and would have been read as a version named `edit`.
export type WorkflowPage = "edit";

// The suffix the editor is asked for by. Kept beside the URL builder that writes it and the reader that
// takes it off again, so the two cannot drift.
export const EDIT_SUFFIX = ".edit";

// The console URL opening a repository path, e.g. /Workflows/review -> /admin/workflows/Workflows/review,
// and its editor -> /admin/workflows/Workflows/review/v2.edit.
export function adminUrl(repositoryPath: string, page?: WorkflowPage): string {
  const url = `${ADMIN_ROOT}${repositoryPath}`;
  return page === undefined ? url : `${url}${EDIT_SUFFIX}`;
}

// What a console URL below ADMIN_ROOT is about — not decidable from the repository path alone, since
// a homepage can sit at any depth (/Content/Workflows/review could be a version of /Content/Workflows
// or a workflow of /Content/Workflows) — so resolving one needs the homepages this instance actually has.
export type ConsoleTarget =
  | { kind: "root" }
  | { kind: "homepage"; path: string }
  // A workflow and a version, with the homepage they are stored in, which their pages lead back to
  | { kind: "workflow"; path: string; homepage: WorkflowHomepage }
  // Editing is a mode of the version's own page rather than a page below it.
  | { kind: "version"; path: string; homepage: WorkflowHomepage; editing: boolean }
  | { kind: "unknown" };

const UNKNOWN: ConsoleTarget = { kind: "unknown" };

const ROOT: ConsoleTarget = { kind: "root" };

// What a console URL addresses, resolved against the homepages workflows are stored in. Depth is
// counted from the homepage rather than the root — the only fixed part of the shape, since below a
// homepage it's always homepage/workflow/version — found as the longest homepage the URL starts with
// (in case one is nested inside another), with what remains saying which of the three it is about.
// Nothing below a version is a page, since the one page that opens on a version is asked for by the
// .edit suffix — so no path segment is reserved and every one is repository content.
export function consoleTarget(url: string, homepages: readonly WorkflowHomepage[]): ConsoleTarget {
  const address = url.replace(/\.html$/, "").replace(/\/+$/, "");
  // The editor is asked for on top of the URL of what it edits, so the rest is read by taking the
  // suffix off and asking what is left. It asks for a mode of a page, so it means something only
  // where there is a mode to ask for: anywhere but on a version it names nothing, rather than being
  // quietly ignored on a URL the console would otherwise never produce.
  if (address.endsWith(EDIT_SUFFIX)) {
    const edited = consoleTarget(address.slice(0, -EDIT_SUFFIX.length), homepages);
    return edited.kind === "version" ? { ...edited, editing: true } : UNKNOWN;
  }
  if (address !== ADMIN_ROOT && !address.startsWith(`${ADMIN_ROOT}/`)) {
    return UNKNOWN;
  }
  const tail = address.slice(ADMIN_ROOT.length);
  // The console's own root addresses no repository path: a listing belongs to a homepage, so the
  // root is a way in rather than a page, and stands for the homepage every deployment has. It is
  // the one URL here that is decided without the homepages, so a caller may resolve it before
  // discovery has landed.
  if (tail === "") {
    return ROOT;
  }
  // The longest match, so a homepage stored under another homepage's path wins over its container
  const homepage = homepages
    .filter(candidate => tail === candidate.path || tail.startsWith(`${candidate.path}/`))
    .reduce<WorkflowHomepage | undefined>((longest, candidate) =>
      longest === undefined || candidate.path.length > longest.path.length ? candidate : longest, undefined);
  if (homepage === undefined) {
    return UNKNOWN;
  }
  const below = tail.slice(homepage.path.length).split("/").filter(Boolean);
  switch (below.length) {
    case 0:
      return { kind: "homepage", path: homepage.path };
    case 1:
      return { kind: "workflow", path: `${homepage.path}/${below[0]}`, homepage };
    case 2:
      return { kind: "version", path: `${homepage.path}/${below[0]}/${below[1]}`, homepage, editing: false };
    default:
      return UNKNOWN;
  }
}
