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

/**
 * Serialization selectors passed as query parameters instead of written into the path.
 *
 * The reason they can be is that a selector carrying a dot in its own value has to escape it with a
 * backslash, and Jetty refuses a path containing one — encoded or not — unless the deployment loosens
 * its URI compliance for every request it will ever serve. A query string is under no such rule. The
 * `UriComplianceMode=JETTY_11` override that used to loosen it has been dropped now that this exists,
 * so the first test below asserts the refusal directly — it is what keeps the override from quietly
 * coming back.
 *
 * What the rest are here for is the wiring: the selectors are read off the request by a filter, which
 * records them for the request so that every parse during it includes them. Only a live instance can
 * say whether that reaches the serializer, which sits behind `adaptTo(JsonObject.class)`. Two earlier
 * designs passed their unit tests and were dead here — writing the resource's metadata throws, because
 * it is locked, and wrapping the resource changes nothing, because `ResourceWrapper.adaptTo` delegates
 * to the resource it wraps.
 *
 * Every check below is written against a property that is present by default and gone when the
 * selector is honoured, rather than against a whole-body comparison: `/Tags` on the bare platform has
 * no children, so anything phrased in terms of serialization *depth* passes whether or not the
 * parameter was read at all.
 */
test.describe('selectors given as query parameters', () => {
  const TAGS = '/Tags';

  const asAdmin = { headers: adminAuth };

  /** The serialized body of one request, as a plain object. */
  const body = async (request: APIRequestContext, url: string): Promise<Record<string, unknown>> => {
    const response = await request.get(url, asAdmin);
    expect(response.status(), `${url} was not served`).toBe(200);
    return await response.json() as Record<string, unknown>;
  };

  test('a backslash in the path is refused, which is why the parameter exists', async ({ request }) => {
    // Jetty rejects the request target before Sling ever sees it. This is the encoded form, which is
    // the harder case: a literal backslash is refused by every compliance mode, while `%5C` was
    // allowed by the one this deployment used to set. If this starts passing, that override is back.
    const response = await request.get(`${TAGS}.dataOption:x=a%5C.b.json`, asAdmin);

    expect(response.status(), 'an escaped dot in the path was accepted').toBeGreaterThanOrEqual(400);
  });

  test('the default serialization carries the properties these tests switch off', async ({ request }) => {
    // The baseline every other test here is a departure from. Without it, a selector that silently did
    // nothing and one that removed a property that was never there would look the same.
    const plain = await body(request, `${TAGS}.json`);

    expect(plain).toHaveProperty('@path');
    expect(plain).toHaveProperty('title');
    expect(plain).toHaveProperty('sling:resourceType');
  });

  test('a selector in the query is honoured, and means the same as one in the path', async ({ request }) => {
    // `-identify` switches off the processor that adds @path and @name
    const inQuery = await body(request, `${TAGS}.json?selector=-identify`);

    expect(inQuery).not.toHaveProperty('@path');
    expect(inQuery).toEqual(await body(request, `${TAGS}.-identify.json`));
  });

  test('a repeated parameter contributes every selector, not just the first', async ({ request }) => {
    const both = await body(request, `${TAGS}.json?selector=-identify&selector=-properties`);

    expect(both).not.toHaveProperty('@path');
    expect(both).not.toHaveProperty('title');
    // And the first one alone leaves the properties in place, so the second really did something
    expect(await body(request, `${TAGS}.json?selector=-identify`)).toHaveProperty('title');
  });

  test('a selector whose value contains dots arrives whole', async ({ request }) => {
    // The shape a path cannot carry. Nothing in the bare platform consumes a dotted option value, so
    // what is checked is that it is not split: `simple` is a real processor, and had the value been
    // split on its dots it would have run and dropped the sling: properties.
    const dotted = await body(request, `${TAGS}.json?selector=-identify&selector=dataOption:x=-a.simple.b`);

    expect(dotted).toHaveProperty('sling:resourceType');
    expect(dotted).toEqual(await body(request, `${TAGS}.json?selector=-identify`));
  });

  test('and the same value split by hand does change the serialization', async ({ request }) => {
    // The negative control for the test above: proof that `simple` in that position would have been
    // noticed, so its absence there means the value stayed in one piece
    const split = await body(request, `${TAGS}.json?selector=-identify&selector=simple`);

    expect(split).not.toHaveProperty('sling:resourceType');
  });
});
