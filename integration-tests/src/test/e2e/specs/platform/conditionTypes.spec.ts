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

type Entry = Record<string, unknown>;

/**
 * The `/ConditionTypes` catalogue the condition builder reads: the comparators, aggregators and operand sources in
 * the registry, and for each source where to ask for its candidates. Nothing is stored under the catalogue, so a bare
 * platform already lists every built-in type.
 */
test.describe('the condition types', () => {
  test('are listed by the catalogue, from the services that evaluate them', async ({ request }) => {
    const response = await request.get('/ConditionTypes.doc.json', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();
    const items = ((await response.json()) as { items: Record<string, Entry[]> }).items;

    expect(items.Comparators.map(entry => entry.name)).toContain('greater or equal');
    expect(items.Comparators[0]).toMatchObject({ name: 'equals', phrase: 'is', unary: false });
    expect(items.Aggregators.map(entry => entry.name)).toEqual([ 'count', 'sum', 'avg', 'min', 'max' ]);

    const sources = Object.fromEntries(items.Sources.map(entry => [ String(entry.name), entry ]));
    expect(sources.answer).toMatchObject({
      valueLabel: 'Question', candidates: '{+path}.conditionCandidates.json?source=answer',
    });
    expect(sources.tags).toMatchObject({ type: 'text', multiple: true, choices: '/Tags.doc.json' });
    expect(sources.literal).toMatchObject({ offered: false });
  });

  test('are also documented for people to read', async ({ request }) => {
    const response = await request.get('/ConditionTypes.doc.md', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();
    const page = await response.text();
    expect(page).toContain('# Conditions');
    expect(page).toContain('## Comparators');
  });

  test('list no candidates where there is nothing to name', async ({ request }) => {
    // Outside any schema version, the answer source has no question to offer
    const none = await request.get('/ConditionTypes.conditionCandidates.json?source=answer', { headers: adminAuth });
    expect(none.status()).toBe(200);
    expect(await none.json()).toEqual([]);

    const unnamed = await request.get('/ConditionTypes.conditionCandidates.json', { headers: adminAuth });
    expect(unnamed.status()).toBe(400);
  });
});
