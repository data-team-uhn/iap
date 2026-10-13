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

import { expect, test, type APIRequestContext } from '@playwright/test';

import { adminAuth } from '../../../support/auth';

/**
 * THE STORY: every process the platform runs on its own can be opened in the editor and still be itself.
 *
 * The system workflows under /SystemWorkflows are installed with their flow nodes written out by hand,
 * because they are not `bpmnAuthoritative`: a reparse must not be able to throw away the graph the
 * platform depends on to boot. Each one also ships a diagram, which is what an administrator opens when
 * she wants to see - or change - what the platform does on a create, a save, or a promotion.
 *
 * So each definition says the same thing twice, and nothing at runtime forces the two to agree. Miriam
 * finds out whether they do, the only way anyone can: she opens a new version of each system workflow
 * from that workflow's own diagram, which makes the new version's graph *derived*, and compares it with
 * the graph the platform shipped.
 *
 * A story rather than a spec because it mutates: each definition gains a draft version beside the active
 * one until the story throws its drafts away. A draft runs nothing - version matching only ever picks an
 * active version - but while it exists, the listing a concurrent spec reads is not the one it started
 * from, which is exactly what the stories/specs split exists for.
 *
 * The unit-level counterpart is `ShippedDefinitionParityTest`, which compares the same two things against
 * the sources rather than a running instance. This one is what proves the pieces are actually installed
 * together: the vocabulary each service task needs, the node types it is stored as, and the diagram
 * itself, all in one repository, parsed by the commit editor rather than by a test harness.
 */

/** A tree as the JSON serializers hand it over: a child node is a nested object, everything else a value. */
type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

type JsonObject = Record<string, JsonValue>;

/**
 * What the two sides are allowed to differ about. All of these are the repository's rather than the
 * diagram's: the two timestamps are stamped by whoever wrote the node, the identity is handed out on
 * creation, the resource types restate the primary type, `catching` is autocreated and protected by
 * every event node type, so no diagram has anything to say about it, and `@path` is where the JSON
 * serializer found the node, which is a different version on each side.
 */
const REPOSITORY_OWNED = new Set([
  'jcr:created',
  'jcr:createdBy',
  'jcr:uuid',
  'sling:resourceType',
  'sling:resourceSuperType',
  'catching',
  '@path',
]);

/** The diagram is a file child of a version, and is the input to the comparison rather than part of it. */
const DIAGRAM = 'bpmn.xml';

/** A boundary event's opening tag, under whatever prefix the diagram bound the BPMN namespace to. */
const BOUNDARY_EVENT = /<[\w.-]+:boundaryEvent\b[^>]*>/g;

const ELEMENT_ID = /\bid="([^"]*)"/;

const DECLARES_CANCEL_ACTIVITY = /\bcancelActivity\s*=/;

const isNode = (value: JsonValue): value is JsonObject =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

/**
 * The boundary events of a diagram that state `cancelActivity`, by element id - which is also the name
 * of the node each is stored as.
 *
 * Read per element rather than per file: one diagram may state the attribute on one boundary event and
 * leave another to the default, and only the event that states it has anything to compare.
 */
function cancelActivityDeclared(bpmn: string): Set<string> {
  const declared = new Set<string>();
  for (const [ element ] of bpmn.matchAll(BOUNDARY_EVENT)) {
    const id = ELEMENT_ID.exec(element)?.[1];
    if (id !== undefined && DECLARES_CANCEL_ACTIVITY.test(element)) {
      declared.add(id);
    }
  }
  return declared;
}

/**
 * One flow node, and whatever it holds, with everything neither the diagram nor the descriptor is
 * responsible for taken out.
 *
 * `cancelActivity` is only compared for a boundary event whose own element says it. Where the
 * attribute is absent the node type's own default stands - which a hand-written descriptor is free to
 * spell out and a parse never writes.
 */
function comparable(name: string, node: JsonObject, declared: Set<string>): JsonObject {
  const result: JsonObject = {};
  for (const [ property, value ] of Object.entries(node)) {
    if (REPOSITORY_OWNED.has(property) || (property === 'cancelActivity' && !declared.has(name))) {
      continue;
    }
    result[property] = isNode(value) ? comparable(property, value, declared) : value;
  }
  return result;
}

/**
 * The graph of one version: its child nodes, which are the flow nodes and the arcs stored inside them.
 * A version's own properties - its state, its description, whether its diagram owns its graph - are not
 * part of this: no diagram says anything about them, and the two versions being compared differ in all
 * three by construction.
 */
async function graphOf(request: APIRequestContext, versionPath: string, declared: Set<string>):
Promise<JsonObject> {
  // `-dereference` so that a flowNodeType comes back as the identifier of the vocabulary entry rather
  // than as its expanded content: the identifier is what says the two nodes are the same kind of node.
  const response = await request.get(`${versionPath}.deep.-dereference.infinity.json`, { headers: adminAuth });
  expect(response.ok(), `${versionPath} could not be read back`).toBeTruthy();
  const version = (await response.json()) as JsonObject;
  const graph: JsonObject = {};
  for (const [ name, value ] of Object.entries(version)) {
    if (name !== DIAGRAM && isNode(value)) {
      graph[name] = comparable(name, value, declared);
    }
  }
  return graph;
}

/** The system workflows this instance installed, whichever modules they came from. */
async function systemWorkflows(request: APIRequestContext): Promise<string[]> {
  const response = await request.get('/SystemWorkflows.1.json', { headers: adminAuth });
  expect(response.ok(), '/SystemWorkflows could not be listed').toBeTruthy();
  const listing = (await response.json()) as JsonObject;
  return Object.entries(listing)
    .filter(([ , value ]) => isNode(value) && value['jcr:primaryType'] === 'wf:WorkflowDefinition')
    .map(([ name ]) => name)
    .sort((one, other) => one.localeCompare(other));
}

test.describe('stories: every system workflow can be re-derived from its own diagram', () => {
  // One definition after another against one instance, and each step posts an event that the engine
  // runs to an end event. The default 30s budget is sized for a spec that checks one thing.
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  /** What this run's drafts are called, so a re-run against a long-lived instance does not collide. */
  const LABEL = `parity-${Date.now()}`;

  /** The versions this run opened, by repository path, for the story to throw away afterwards. */
  const versions = new Set<string>();

  // Miriam throws away the drafts she opened, leaving the instance as she found it: a later story that
  // drafts from one of these workflows expects to find only what the platform shipped
  test.afterAll(async ({ playwright, baseURL }) => {
    const request = await playwright.request.newContext({ baseURL });
    const left: string[] = [];
    for (const version of versions) {
      if (!(await request.delete(`${version}?permanent=true`, { headers: adminAuth })).ok()) {
        left.push(version);
      }
    }
    await request.dispose();
    expect(left, 'drafts this story opened were left behind').toEqual([]);
  });

  test('Miriam re-derives every system workflow from its own diagram', async ({ request }) => {
    let definitions: string[] = [];

    await test.step('she finds the processes the platform runs on its own', async () => {
      definitions = await systemWorkflows(request);
      // A bare platform ships the workflow authoring definitions and the submission ones; an empty
      // listing means the initial content never landed, not that there is nothing to check
      expect(definitions.length, 'no system workflows are installed').toBeGreaterThan(0);
    });

    for (const definition of definitions) {
      await test.step(`${definition} derives the graph it was installed with`, async () => {
        const shipped = `/SystemWorkflows/${definition}/v1`;

        // Every workflow the platform ships carries its diagram. One without is another story's, installed
        // or removed while this one runs, since stories run side by side: not this story's to check.
        const diagram = await request.get(`${shipped}/${DIAGRAM}`, { headers: adminAuth });
        if (diagram.status() === 404) {
          test.info().annotations.push({ type: 'skipped', description: `${definition} carries no diagram` });
          return;
        }
        expect(diagram.ok(), `${definition}'s diagram could not be read`).toBeTruthy();
        const bpmn = await diagram.text();

        // The same request the editor makes when somebody opens a new version: the diagram travels
        // with it, so a version with no diagram is never an observable state.
        const created = await request.post(`/SystemWorkflows/${definition}.createVersion.json`, {
          headers: adminAuth,
          multipart: {
            version: LABEL,
            [DIAGRAM]: { name: DIAGRAM, mimeType: 'application/xml', buffer: Buffer.from(bpmn, 'utf-8') },
          },
          maxRedirects: 0,
        });
        expect(created.status(), `${definition} refused a new version: ${await created.text()}`).toBe(302);
        const derived = created.headers().location;
        expect(derived, `${definition} created a version without saying where`).toBeTruthy();
        // Thrown away by repository path, which is what a Location that came back absolute has to be
        // reduced to
        versions.add(derived.replace(/^https?:\/\/[^/]+/, ''));

        // The new version is bpmnAuthoritative, so its graph is the parse's alone
        const declared = cancelActivityDeclared(bpmn);
        expect(await graphOf(request, derived, declared),
          `${definition}'s diagram and its installed flow nodes disagree`)
          .toEqual(await graphOf(request, shipped, declared));
      });
    }
  });

  // -----------------------------------------------------------------------------------------------
  // THE REST OF THE STORY, still prose because the platform cannot yet tell it:
  //
  //   She edits one of these diagrams and the platform behaves differently afterwards.
  //
  // Waiting on: the shipped definitions becoming bpmnAuthoritative. Until they are, an edited diagram
  // is reparsed into flow nodes that the descriptor's own are not replaced by, so what the engine
  // runs is still the installed graph. This story is what has to stay green for that switch to be
  // safe, which is why it is written now rather than then.
  // -----------------------------------------------------------------------------------------------
});
