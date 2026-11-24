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

import { adminAuth } from '../../support/auth';
import {
  CURRENT_REGISTRY_LABEL, DATA_REQUIREMENT_NAME, DATA_STUDY_SCHEMA_VERSION, DATA_STUDY_WORKFLOW_VERSION, IN_BOTH,
  ONLY_IN_V1, ONLY_IN_V2, REGISTRY,
} from '../../support/dataStudyDemo';

/** The workflow version with its flow nodes, as the engine derived them from the diagram. */
interface DerivedWorkflow {
  bpmnAuthoritative?: boolean;
  fillIn?: { performers?: string[] };
  markSubmitted?: { handler?: string; tag?: string };
  releaseData?: { performers?: string[]; outcomeOptions?: string[] };
  recordReview?: { handler?: string; requirement?: string };
  decision?: { toApproved?: { 'cond:condition'?: { operandB?: { value?: string[] } } } };
  markApproved?: { handler?: string; tag?: string };
  markRejected?: { handler?: string; tag?: string };
}

async function derivedWorkflow(request: APIRequestContext): Promise<DerivedWorkflow> {
  const response = await request.get(`${DATA_STUDY_WORKFLOW_VERSION}.deep.-dereference.infinity.json`,
    { headers: adminAuth });
  expect(response.ok()).toBeTruthy();
  return (await response.json()) as DerivedWorkflow;
}

/** The keys of the fields in a catalogue version, read from its JSON three levels deep. */
async function fieldKeysOf(request: APIRequestContext, version: string): Promise<string[]> {
  const response = await request.get(`${REGISTRY}/${version}.3.json`, { headers: adminAuth });
  expect(response.ok()).toBeTruthy();
  const children = (node: unknown): unknown[] =>
    Object.values(node as Record<string, unknown>).filter(value => typeof value === 'object' && value !== null);
  return children(await response.json()).flatMap(database => children(database))
    .flatMap(collection => children(collection))
    .map(field => (field as { key?: string }).key)
    .filter((key): key is string => key !== undefined);
}

/**
 * The retrospective data study demo, as it is installed.
 */
test.describe('the data study demo', () => {
  test('installs the registry, published twice', async ({ request }) => {
    const response = await request.get(`${REGISTRY}.2.json`, { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const registry = (await response.json()) as {
      'jcr:primaryType'?: string;
      title?: string;
      v1?: { version?: string; tags?: string[] };
      v2?: { version?: string; tags?: string[] };
    };
    expect(registry['jcr:primaryType']).toBe('datareq:Catalogue');
    expect(registry.title).toBe('Demo clinical registry');
    expect(registry.v1?.tags).toEqual([ 'retired' ]);
    expect(registry.v2?.tags).toEqual([ 'active' ]);
    expect(registry.v2?.version).toBe(CURRENT_REGISTRY_LABEL);
  });

  test('serves a whole catalogue version, three levels down, with each field\'s key', async ({ request }) => {
    // What a browser fetches to draw the tree: databases, their collections, and the fields beneath
    const response = await request.get(`${REGISTRY}/v2.3.json`, { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as {
      people?: {
        identifier?: string;
        Person?: { birthYear?: { identifier?: string; key?: string; phi?: boolean } };
      };
    };
    expect(version.people?.identifier).toBe('people');
    expect(version.people?.Person?.birthYear?.identifier).toBe('birthYear');
    expect(version.people?.Person?.birthYear?.key).toBe(IN_BOTH);
    expect(version.people?.Person?.birthYear?.phi).toBe(false);
  });

  test('the two versions differ in both directions', async ({ request }) => {
    const older = await fieldKeysOf(request, 'v1');
    const newer = await fieldKeysOf(request, 'v2');

    expect(older).toContain(ONLY_IN_V1);
    expect(newer).not.toContain(ONLY_IN_V1);
    expect(newer).toContain(ONLY_IN_V2);
    expect(older).not.toContain(ONLY_IN_V2);
  });

  test('installs a schema whose data requirement names the registry', async ({ request }) => {
    const response = await request.get(`${DATA_STUDY_SCHEMA_VERSION}/${DATA_REQUIREMENT_NAME}.json`,
      { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const requirement = (await response.json()) as {
      'jcr:primaryType'?: string;
      label?: string;
      catalogue?: { '@path'?: string };
    };
    expect(requirement['jcr:primaryType']).toBe('datareq:DataRequirement');
    expect(requirement.label).toBe('Which data do you need?');
    expect(requirement.catalogue?.['@path']).toBe(REGISTRY);
  });

  test('installs the workflow, and ships its diagram as a file', async ({ request }) => {
    const version = await request.get(`${DATA_STUDY_WORKFLOW_VERSION}.json`, { headers: adminAuth });
    expect(version.ok()).toBeTruthy();
    expect(((await version.json()) as { tags?: string[] }).tags).toContain('active');

    const diagram = await request.get(`${DATA_STUDY_WORKFLOW_VERSION}/bpmn.xml`, { headers: adminAuth });
    expect(diagram.ok()).toBeTruthy();
    expect(diagram.headers()['content-type']).toContain('application/xml');
  });

  test.describe('derives the flow nodes from the diagram', () => {
    test('rather than from the descriptor', async ({ request }) => {
      expect((await derivedWorkflow(request)).bpmnAuthoritative).toBe(true);
    });

    test('has the researcher fill the study in, and marks it submitted', async ({ request }) => {
      const workflow = await derivedWorkflow(request);

      expect(workflow.fillIn?.performers).toEqual([ '@creator' ]);
      expect(workflow.markSubmitted?.handler).toBe('addTag');
      expect(workflow.markSubmitted?.tag).toBe('submitted');
    });

    test('has a data steward decide whether to release the data', async ({ request }) => {
      const workflow = await derivedWorkflow(request);

      expect(workflow.releaseData?.performers).toEqual([ 'data-stewards' ]);
      expect(workflow.releaseData?.outcomeOptions).toEqual([ 'approved', 'rejected' ]);
    });

    test('keeps the parameters the service task vocabulary maps', async ({ request }) => {
      const workflow = await derivedWorkflow(request);

      expect(workflow.recordReview?.handler).toBe('recordReview');
      expect(workflow.recordReview?.requirement).toBe('approval');
    });

    test('takes the decision guard from the System View in the diagram', async ({ request }) => {
      const workflow = await derivedWorkflow(request);

      expect(workflow.decision?.toApproved?.['cond:condition']?.operandB?.value).toEqual([ 'approved' ]);
    });

    test('ends with the study tagged approved or rejected', async ({ request }) => {
      const workflow = await derivedWorkflow(request);

      expect(workflow.markApproved?.handler).toBe('addTag');
      expect(workflow.markApproved?.tag).toBe('approved');
      expect(workflow.markRejected?.handler).toBe('addTag');
      expect(workflow.markRejected?.tag).toBe('rejected');
    });
  });
});
