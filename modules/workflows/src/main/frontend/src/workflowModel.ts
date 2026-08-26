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

// The workflow definitions' canonical home. Others may exist — the platform's own under
// /SystemWorkflows, another location's mirrored locally — and are discovered rather than listed
// here; this one is the entry point that discovery is asked through, and always exists.
export const WORKFLOWS_ROOT = "/Workflows";

// The lifecycle of a workflow version, as stored in its `state` property. A version is authored as
// a DRAFT, may go on TRIAL before the workflow commits to it, is promoted to ACTIVE once it is ready
// to run, and is RETIRED when a later version takes over: the instances already running against it
// carry on, but no new ones start.
//
// Only a DRAFT may be edited. Every later state is one something may be following, or about to
// follow, so changing its diagram would change a process out from under whatever is executing it —
// which is why a trial that needs another look goes back to being a draft rather than being edited
// where it stands.
export const WORKFLOW_STATES = [ "DRAFT", "TRIAL", "ACTIVE", "RETIRED" ] as const;

export type WorkflowState = typeof WORKFLOW_STATES[number];

// How each state is named on screen, and the chip colour that carries it. Active is the one thing
// running, so it is the only state given a colour with weight behind it.
export const STATE_LABELS: Record<WorkflowState, string> = {
  DRAFT: "Draft",
  TRIAL: "Trial",
  ACTIVE: "Active",
  RETIRED: "Retired",
};

// The state a version is in. Anything unrecognized — an absent property on older content, a value
// from a newer platform — reads as a draft, which is the state nothing is ever instantiated from:
// the same reading the server's own model takes, and for the same reason.
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

// One homepage and how many workflows it holds, as a summary displays it. The server stops
// counting once it is far enough past the page it was asked for, so a very large collection is
// reported as a lower bound rather than making it count everything for a number in a widget.
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
  // Whether new instances may be created from this workflow, which is exactly whether one of its
  // versions is active. Not a property of the definition: a workflow runs through a version or not
  // at all, so a stored flag would only be a second answer to the same question, free to disagree.
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

// Reads one workflow definition and the versions under it.
//
// Two levels is exactly what the page renders: the definition's own properties, and the versions
// under it. The depth selector both turns child serialization on and stops the traversal there, so a
// version's own children -- the diagram file, and the parsed flow nodes -- are left as bare paths
// instead of being dragged into the listing.
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

// The discovery, kept for the rest of the session once it has landed.
//
// Every console URL below /admin/workflows is read against this list — which of the three things a
// path is, is a question about how deep it sits below its homepage — so it is asked for on every
// navigation rather than once per page. Homepages are created by a bundle's repoinit and change only
// when one is installed or removed, which is a restart and so a new session; caching them for the
// life of this one is therefore not a staleness this can observe. An in-flight request is shared, so
// several pages mounting at once ask once between them.
let discovered: WorkflowHomepage[] | null = null;
let discovery: Promise<WorkflowHomepage[]> | null = null;

// The homepages the current user may list workflows from, the queried one first.
//
// Asked of /Workflows, which always exists, and answered with every homepage holding workflow
// definitions that the user can read — so a deployment that adds one (the platform's own system
// workflows, another location's) needs nothing configured here. A failure to ask leaves the caller
// with the one homepage everybody has, rather than with nothing at all.
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

// How many workflows each homepage the current user may read holds.
//
// One count per homepage, asked of the same pagination endpoint the grids list through: a page of
// no rows at all, which is a count and nothing else. A summary wanting the numbers without the
// listings is exactly what that answers.
// Counted one homepage at a time, and settled rather than joined: the homepages are independent of
// each other, so one that cannot be counted — a tree this user may list but not read into, a request
// that failed on its own — costs its own number and nothing else. Joining them would let it blank
// every other count too, which is the opposite of what discovering homepages separately is for.
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

// The diagram is an nt:file child of the version node rather than one of its properties, so it is
// fetched and posted on its own path; listing the versions no longer carries every diagram with it.
// The extension earns its keep: Sling types a file from its name, so without it every diagram the
// repository serves would be an untyped binary.
export const BPMN_FILE = "bpmn.xml";

// The diagram as a multipart part of an event's payload, under the name of the file it will be
// stored as. Not a Sling POST servlet path (`./bpmn.xml`) and no type hint: these requests are
// events handed to the workflow engine rather than writes, so what the part is called is a payload
// key the handler looks up, and where it ends up in the repository is the handler's business.
export function bpmnUpload(xml: string, body: FormData = new FormData()): FormData {
  body.set(BPMN_FILE, new File([xml], BPMN_FILE, { type: "application/xml" }));
  return body;
}

// The diagram a brand new workflow version starts from: a start event, one user task, an end event.
// Something to open the editor on, rather than an empty canvas.
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

// Where a workflow, one of its versions, or a version's editor lives in the administration console.
// The repository path is carried in the URL, which is what lets one page serve the workflows of any
// homepage: /admin/workflows/Workflows/review, /admin/workflows/SystemWorkflows/newEntity/1-0.
export const ADMIN_ROOT = "/admin/workflows";

// The page a console URL opens on top of a repository path. Only the editor names itself: viewing a
// workflow or a version is what its own path means, so there is nothing for those to say.
//
// It is named in the query rather than in the path because the two are not one inside the other:
// viewing a version and editing it are the same thing seen two ways, reached from the same place and
// neither reporting more than the other. A path segment would have said otherwise everywhere a URL's
// ancestors are read as a hierarchy — the breadcrumb trail most of all, which showed the editor as a
// page below the viewer.
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

// The page a console URL's query asks for, given the query string (location.search) as it stands. A
// parameter naming no page this knows of asks for nothing, the same as a URL that named none: what a
// version's own path means is the version, so there is always something to show.
export function consolePage(search: string): WorkflowPage | undefined {
  const asked = new URLSearchParams(search).get(PAGE_PARAM);
  return asked === "edit" ? asked : undefined;
}

// What a console URL below ADMIN_ROOT is about. The repository path alone does not say which of
// these it is — a homepage sits at no predictable depth, so /Content/Workflows/review could be read
// as a version of /Content/Workflows just as well as a workflow of /Content/Workflows — which is why
// resolving one takes the homepages this instance actually has.
export type ConsoleTarget =
  | { kind: "homepage"; path: string }
  | { kind: "workflow"; path: string }
  | { kind: "version"; path: string }
  | { kind: "unknown" };

const UNKNOWN: ConsoleTarget = { kind: "unknown" };

// What a console URL addresses, resolved against the homepages workflows are stored in.
//
// Depth is counted from the homepage rather than from the root, because that is the only part of the
// shape that is fixed: a homepage may be anywhere, but below one it is always homepage / workflow /
// version. So the homepage is found first — the longest one the URL starts with, since one homepage
// may be stored inside another — and what remains says which of the three the URL is about.
//
// Nothing below a version is a page: which page is opened on a version is asked for in the query
// (see consolePage), so every segment of the path is repository content and no name is reserved.
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
