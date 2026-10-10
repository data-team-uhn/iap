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

import { expect, type Locator, type Page } from '@playwright/test';

/** A small phone's screen, the narrowest a deployment is expected to work on. */
export const PHONE = { width: 360, height: 740 } as const;

/**
 * What reaches past the right edge of the screen inside the given element, by what it says. Measured against the
 * phone's width: a phone's browser widens its layout to whatever is too wide, so the window's own width says nothing.
 */
export const pastTheEdge = (scope: Locator): Promise<string[]> => scope.evaluate((root, width) =>
  Array.from(root.querySelectorAll<HTMLElement>('*'))
    .filter(element => element.getBoundingClientRect().right > width + 0.5)
    .map(element => element.innerText.slice(0, 40) || element.tagName), PHONE.width);

/** Checks nothing on the page runs past the phone's edge, once everything it loads is in, the application bar's
 * entries among them. */
export async function fitsTheScreen(page: Page): Promise<void> {
  await page.waitForLoadState('networkidle');
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(PHONE.width);
  expect(await pastTheEdge(page.locator('body'))).toEqual([]);
}
