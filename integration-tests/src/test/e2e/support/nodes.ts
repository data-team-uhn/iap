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

import { expect, type APIRequestContext } from '@playwright/test';

import { adminAuth } from './auth';

/** A child node in a node's JSON, typed for the properties the specs read. */
export interface SerializedChild {
  'jcr:primaryType': string;
  '@path'?: string;
  tags?: string[];
  version?: string;
}

/** The child nodes in a node's JSON, whatever their names and types. */
export function childNodes(node: unknown): Record<string, unknown>[] {
  if (typeof node !== 'object' || node === null) {
    return [];
  }
  return Object.values(node).filter((child): child is Record<string, unknown> =>
    typeof child === 'object' && child !== null && !Array.isArray(child));
}

/** The child nodes of one node type in a node's JSON, whatever their names. */
export function childrenOfType(node: unknown, type: string): SerializedChild[] {
  return childNodes(node).filter((child): child is Record<string, unknown> & SerializedChild =>
    child['jcr:primaryType'] === type);
}

/** The tags a node carries itself, as the repository serves them. */
export async function tagsOf(request: APIRequestContext, path: string): Promise<string[]> {
  const response = await request.get(`${path}.json`, { headers: adminAuth });
  expect(response.ok(), `${path} could not be read`).toBeTruthy();
  return ((await response.json()) as { tags?: string[] }).tags ?? [];
}
