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

import { expect, test } from '@playwright/test';

import { adminAuth } from '../../support/auth';
import { ONLY_IN_V1, ONLY_IN_V2, REGISTRY } from '../../support/dataStudyDemo';

/**
 * The retrospective data study demo, as it is installed.
 *
 * The demo shows one thing no other content does: a selection is answered against a *version* of a catalogue.
 * These tests only look. The stories beside them start studies, choose their data and send them.
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
    // Exactly one is active, and it is the newer: that is what a study started today is answered against
    expect(registry.v1?.tags).toEqual([ 'retired' ]);
    expect(registry.v2?.tags).toEqual([ 'active' ]);
    expect(registry.v2?.version).toBe('2026-08');
  });

  test('serves a whole catalogue version, three levels down', async ({ request }) => {
    // What a browser fetches to draw the tree: databases, their collections, and the fields beneath
    const response = await request.get(`${REGISTRY}/v2.3.json`, { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as {
      people?: {
        identifier?: string;
        Person?: { birthYear?: { identifier?: string; phi?: boolean } };
      };
    };
    expect(version.people?.identifier).toBe('people');
    expect(version.people?.Person?.birthYear?.identifier).toBe('birthYear');
    expect(version.people?.Person?.birthYear?.phi).toBe(false);
  });

  test('the two versions differ in both directions', async ({ request }) => {
    const field = (version: string, key: string) =>
      request.get(`${REGISTRY}/${version}/${key}.json`, { headers: adminAuth });

    expect((await field('v1', ONLY_IN_V1)).ok()).toBeTruthy();
    expect((await field('v2', ONLY_IN_V1)).status()).toBe(404);
    expect((await field('v2', ONLY_IN_V2)).ok()).toBeTruthy();
    expect((await field('v1', ONLY_IN_V2)).status()).toBe(404);
  });

  test('installs a schema whose data requirement names the registry', async ({ request }) => {
    const response = await request.get('/Schemas/dataStudy/v1/data.json', { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const requirement = (await response.json()) as {
      'jcr:primaryType'?: string;
      label?: string;
      catalogue?: { '@path'?: string };
    };
    expect(requirement['jcr:primaryType']).toBe('datareq:DataRequirement');
    expect(requirement.label).toBe('Which data do you need?');
    // The catalogue, not one of its versions: which version is used is decided when somebody chooses
    expect(requirement.catalogue?.['@path']).toBe(REGISTRY);
  });

  test('installs the workflow, and ships its diagram as a file', async ({ request }) => {
    const version = await request.get('/Workflows/dataStudy/v1.json', { headers: adminAuth });
    expect(version.ok()).toBeTruthy();
    expect(((await version.json()) as { tags?: string[] }).tags).toContain('active');

    const diagram = await request.get('/Workflows/dataStudy/v1/bpmn.xml', { headers: adminAuth });
    expect(diagram.ok()).toBeTruthy();
    expect(diagram.headers()['content-type']).toContain('application/xml');
  });

  test('derives the flow nodes from the diagram rather than from the descriptor', async ({ request }) => {
    const response = await request.get('/Workflows/dataStudy/v1.deep.-dereference.infinity.json',
      { headers: adminAuth });
    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as {
      bpmnAuthoritative?: boolean;
      fillIn?: { performers?: string[] };
      markSubmitted?: { handler?: string; tag?: string };
      releaseData?: { performers?: string[]; outcomeOptions?: string[] };
      recordReview?: { handler?: string; requirement?: string };
      decision?: { toApproved?: { 'cond:condition'?: { operandB?: { value?: string[] } } } };
      studyApproved?: { hostTag?: string };
      studyRejected?: { hostTag?: string };
    };

    expect(version.bpmnAuthoritative).toBe(true);
    expect(version.fillIn?.performers).toEqual([ '@creator' ]);
    expect(version.markSubmitted?.handler).toBe('addTag');
    expect(version.markSubmitted?.tag).toBe('submitted');
    expect(version.releaseData?.performers).toEqual([ 'data-stewards' ]);
    expect(version.releaseData?.outcomeOptions).toEqual([ 'approved', 'rejected' ]);
    // Present only because the service task vocabulary maps it: an unmapped parameter is dropped silently
    expect(version.recordReview?.handler).toBe('recordReview');
    expect(version.recordReview?.requirement).toBe('approval');
    // The guard comes from the System View in the diagram's extensionElements
    expect(version.decision?.toApproved?.['cond:condition']?.operandB?.value).toEqual([ 'approved' ]);
    expect(version.studyApproved?.hostTag).toBe('approved');
    expect(version.studyRejected?.hostTag).toBe('rejected');
  });
});
