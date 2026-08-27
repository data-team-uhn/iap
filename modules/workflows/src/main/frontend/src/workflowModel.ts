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
// lifecycle state reads, and how one workflow's versions are listed.

import { fetchEntityPage } from "@iap/frontend-commons/entityGrid/pagination";
import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { RequestError } from "@iap/frontend-commons/requestFailure";

// The workflow definitions' canonical home; others may exist (the platform's own under
// /SystemWorkflows, another location's mirrored locally) but are discovered rather than listed here —
// this one is only the entry point discovery is asked through, and always exists.
export const WORKFLOWS_ROOT = "/Workflows";

// The lifecycle of a workflow version, stored in its `state` property: authored as a DRAFT, optionally
// trialled, promoted to ACTIVE to run, and RETIRED once a later version supersedes it — a retired
// version's own running instances carry on, but no new ones start from it.
//
// Only a DRAFT may be edited: every later state may already be driving a running process, so a trial
// that needs changes goes back to being a draft rather than being edited in place.
export const WORKFLOW_STATES = [ "DRAFT", "TRIAL", "ACTIVE", "RETIRED" ] as const;

export type WorkflowState = typeof WORKFLOW_STATES[number];

// How each state is named on screen, and how much colour weight its chip gets — Active alone carries
// weight, since it's the one state actually running.
export const STATE_LABELS: Record<WorkflowState, string> = {
  DRAFT: "Draft",
  TRIAL: "Trial",
  ACTIVE: "Active",
  RETIRED: "Retired",
};

// Anything unrecognized — an absent property on older content, a value from a newer platform — reads
// as a draft, the state nothing is ever instantiated from, matching how the server's own model reads it.
export function stateOf(raw: unknown): WorkflowState {
  return WORKFLOW_STATES.includes(raw as WorkflowState) ? raw as WorkflowState : "DRAFT";
}

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
  state: WorkflowState;
  lastModified: string;
}

// One workflow definition with the versions stored under it, as its own page displays it.
export interface WorkflowSummary {
  path: string;
  name: string;
  title: string;
  // Whether new instances may be created from this workflow — derived from whether one of its
  // versions is active, rather than stored separately, so the two can never disagree.
  active: boolean;
  created: string;
  lastModified: string;
  versions: WorkflowVersionSummary[];
}

function isNode(value: unknown): value is JcrNode {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
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
        state: stateOf(version.state),
        lastModified: text(version["jcr:lastModified"]),
      };
    });
}

// Two levels is exactly what the page renders — the definition's own properties and the versions
// under it — so the depth selector both turns on child serialization and stops it there, leaving a
// version's own children (the diagram file, the parsed flow nodes) as bare paths instead of dragging
// them into the response.
//
// The status is read off the response before the body is parsed, because a refusal answers with an
// error page rather than with JSON: parsing it first reports how the body disappointed the parser,
// which says nothing about what was refused.
export function loadWorkflow(fetchUtil: AuthenticatedFetch, path: string): Promise<WorkflowSummary> {
  return fetchUtil(`${path}.2.json`)
    .then(response => {
      if (!response.ok) {
        throw new RequestError(response.status);
      }
      return response.json() as Promise<JcrNode>;
    })
    .then(definition => {
      const versions = parseVersions(path, definition);
      return {
        path,
        name: path.slice(path.lastIndexOf("/") + 1),
        title: text(definition.title) || path.slice(path.lastIndexOf("/") + 1),
        active: versions.some(version => version.state === "ACTIVE"),
        created: text(definition["jcr:created"]),
        lastModified: text(definition["jcr:lastModified"]),
        versions,
      };
    });
}

// Cached for the life of the session: every console URL below /admin/workflows is resolved against
// this list, so asking once per navigation would be wasteful. Homepages change only when a bundle
// installs or is removed — a restart, hence a new session — so this cache is never stale. An
// in-flight request is shared, so concurrent page mounts ask the server only once.
let discovered: WorkflowHomepage[] | null = null;
let discovery: Promise<WorkflowHomepage[]> | null = null;

// The homepages the current user may list workflows from, the queried one first — asked of
// /Workflows (which always exists) and answered with every homepage the user can read, so a
// deployment that adds one (the platform's own system workflows, another location's) needs nothing
// configured here. A failed ask falls back to the one homepage everybody has, rather than to nothing.
export function loadWorkflowHomepages(fetchUtil: AuthenticatedFetch): Promise<WorkflowHomepage[]> {
  if (discovered) {
    return Promise.resolve(discovered);
  }
  discovery ??= fetchHomepages(fetchUtil)
    .then(homepages => discovered = homepages)
    .finally(() => discovery = null);
  return discovery;
}

// Forgets the discovery, so that the next ask goes to the server. For tests, and for a caller that
// has reason to believe the set of homepages has changed under it.
export function forgetWorkflowHomepages(): void {
  discovered = null;
  discovery = null;
}

function fetchHomepages(fetchUtil: AuthenticatedFetch): Promise<WorkflowHomepage[]> {
  return fetchUtil(`${WORKFLOWS_ROOT}.homepages.json`)
    .then(response => {
      if (!response.ok) {
        throw new RequestError(response.status);
      }
      return response.json() as Promise<{ homepages?: unknown }>;
    })
    .then(answer => (Array.isArray(answer.homepages) ? answer.homepages : [])
      .filter(isNode)
      .map(homepage => ({ path: text(homepage.path), title: text(homepage.title) }))
      .filter(homepage => homepage.path !== ""))
    .catch((error: unknown) => {
      console.error("Failed to discover the workflow homepages; listing the default one only", error);
      return [ { path: WORKFLOWS_ROOT, title: "Workflows" } ];
    });
}

// One count per homepage, asked of the pagination endpoint with a page of no rows — exactly a count
// and nothing else, which is what a summary needs. Counted independently and settled rather than
// joined, so a homepage that can't be counted (unreadable, or a failed request) loses only its own
// number instead of blanking every other homepage's count too.
export function loadWorkflowCounts(fetchUtil: AuthenticatedFetch): Promise<WorkflowHomepageCount[]> {
  return loadWorkflowHomepages(fetchUtil).then(homepages => Promise.allSettled(
    homepages.map(homepage => fetchEntityPage(fetchUtil, { homepage: homepage.path, limit: 0 }))
  ).then(answers => answers.map((answer, index) => {
    const homepage = homepages[index];
    if (answer.status === "rejected") {
      console.error(`Failed to count the workflows in ${homepage.path}`, answer.reason);
      return { ...homepage, atLeast: false };
    }
    return { ...homepage, count: answer.value.totalrows, atLeast: answer.value.totalIsApproximate };
  })));
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
// /admin/workflows/Workflows/review or /admin/workflows/SystemWorkflows/newEntity/1-0.
export const ADMIN_ROOT = "/admin/workflows";

// The page a console URL opens on top of a repository path — only the editor names itself, since
// viewing a workflow or version is just what its own path means. It's a query parameter rather than a
// path segment because viewing and editing a version are the same thing seen two ways, not one nested
// in the other; a path segment would have made the breadcrumb trail show the editor as a page below
// the viewer, which it isn't.
export type WorkflowPage = "edit";

// The query parameter each page is asked for by, so that what the console links to and what it reads
// back cannot drift. Its presence is the whole of the request; it carries no value.
export const PAGE_PARAM = "page";

// The console URL opening a repository path, e.g. /Workflows/review -> /admin/workflows/Workflows/review,
// and its editor -> /admin/workflows/Workflows/review/2-0?page=edit.
export function adminUrl(repositoryPath: string, page?: WorkflowPage): string {
  const url = `${ADMIN_ROOT}${repositoryPath}`;
  return page === undefined ? url : `${url}?${PAGE_PARAM}=${page}`;
}

// An unrecognized (or absent) page parameter asks for nothing, same as a URL that named none — what a
// version's own path means is the version, so there is always something to show.
export function consolePage(search: string): WorkflowPage | undefined {
  const asked = new URLSearchParams(search).get(PAGE_PARAM);
  return asked === "edit" ? asked : undefined;
}

// What a console URL below ADMIN_ROOT is about — not decidable from the repository path alone, since
// a homepage can sit at any depth (/Content/Workflows/review could be a version of /Content/Workflows
// or a workflow of /Content/Workflows) — so resolving one needs the homepages this instance actually has.
export type ConsoleTarget =
  | { kind: "homepage"; path: string }
  | { kind: "workflow"; path: string }
  | { kind: "version"; path: string }
  | { kind: "unknown" };

const UNKNOWN: ConsoleTarget = { kind: "unknown" };

// What a console URL addresses, resolved against the homepages workflows are stored in. Depth is
// counted from the homepage rather than the root — the only fixed part of the shape, since below a
// homepage it's always homepage/workflow/version — found as the longest homepage the URL starts with
// (in case one is nested inside another), with what remains saying which of the three it is about.
// Nothing below a version is a page, since which page opens on a version is asked for in the query
// (see consolePage) — so no path segment is reserved and every one is repository content.
export function consoleTarget(url: string, homepages: readonly string[]): ConsoleTarget {
  const withoutSuffix = url.replace(/\.html$/, "").replace(/\/+$/, "");
  if (withoutSuffix !== ADMIN_ROOT && !withoutSuffix.startsWith(`${ADMIN_ROOT}/`)) {
    return UNKNOWN;
  }
  // The console's own root is not one of these: a listing belongs to a homepage, and nothing is
  // routed here, so there is nothing for it to be about
  const tail = withoutSuffix.slice(ADMIN_ROOT.length);
  // The longest match, so a homepage stored under another homepage's path wins over its container
  const homepage = homepages
    .filter(candidate => tail === candidate || tail.startsWith(`${candidate}/`))
    .reduce<string | undefined>((longest, candidate) =>
      longest === undefined || candidate.length > longest.length ? candidate : longest, undefined);
  if (homepage === undefined) {
    return UNKNOWN;
  }
  const below = tail.slice(homepage.length).split("/").filter(Boolean);
  switch (below.length) {
    case 0:
      return { kind: "homepage", path: homepage };
    case 1:
      return { kind: "workflow", path: `${homepage}/${below[0]}` };
    case 2:
      return { kind: "version", path: `${homepage}/${below[0]}/${below[1]}` };
    default:
      return UNKNOWN;
  }
}
