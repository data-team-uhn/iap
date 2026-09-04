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

import { defineConfig, devices } from '@playwright/test';

import { activeInstances } from './support/instances';

const isCI = Boolean(process.env.CI);

/**
 * The device profile to drive each browser engine with. Also the set of names `playwright.browsers`
 * accepts, so that asking for one that cannot be run says so instead of quietly running Chromium.
 */
const DEVICE_FOR_BROWSER: Record<string, string> = {
  chromium: 'Desktop Chrome',
  firefox: 'Desktop Firefox',
  webkit: 'Desktop Safari',
};

/**
 * The engines to run on, from the same `playwright.browsers` property that decides which ones the build
 * downloads — so the two can never disagree, which is exactly what happens when a browser is installed
 * and then never used.
 */
const browsers = (): string[] => {
  const requested = (process.env.PLAYWRIGHT_BROWSERS ?? 'chromium').trim().split(/\s+/).filter(Boolean);
  const unknown = requested.filter(browser => !(browser in DEVICE_FOR_BROWSER));
  if (unknown.length > 0) {
    throw new Error(
      `Unknown browser(s): ${unknown.join(', ')}. Known: ${Object.keys(DEVICE_FOR_BROWSER).join(', ')}.`,
    );
  }
  return requested.length > 0 ? requested : ['chromium'];
};

/**
 * Two Playwright projects per launched instance per browser, each pointed at its own instance and
 * reading its tests from its own directory. Anything shared — page objects, fixtures — lives outside
 * those directories and is imported.
 *
 * A suite whose instance was not launched simply produces no project, so `-Dit.platform.skip=true` needs
 * no matching change here.
 *
 * Projects are named `<suite>-<browser>` even when only one browser is being run. The suffix is not
 * decoration: a name that appeared only once a second browser was configured would silently invalidate
 * every `--project=` already written down, and a report that says which engine a failure came from is
 * worth more than four saved keystrokes.
 *
 * **Specs observe, stories mutate**, so a suite's `<suite>-stories-<browser>` project depends on its
 * `<suite>-<browser>` one and runs only once that has finished. Much of what a spec asserts is what a
 * freshly launched deployment looks like — an empty taxonomy, "No workflows are defined yet.", an
 * archive nothing has ever been put in — and a story that creates content has no business being in
 * flight while any of that is being checked. Left unsequenced the two do not in fact overlap, since the
 * story worker is queued behind the specs and is slower to create anything than they are to finish; the
 * dependency is here to make that a property rather than a coincidence of worker count and machine
 * speed, and to stop a story that fails partway from leaving content behind that fails every spec which
 * had not run yet. The cost is that a failing spec skips its suite's stories, which is the right way
 * round: the build is already failing, and a story that failed only because the instance was not in the
 * state it says it starts from is worse than no result.
 */
export default defineConfig({
  testDir: './specs',
  outputDir: '../../../target/e2e-results',
  fullyParallel: true,
  // A stray `test.only` must not silently shrink the suite on CI
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  reporter: [
    ['list'],
    ['html', { outputFolder: '../../../target/e2e-report', open: 'never' }],
    // Machine-readable, because the Maven build defers the failure: the test run must not stop the build
    // before the instances have been shut down, so `check-results.mjs` reads this at verify time instead.
    ['json', { outputFile: '../../../target/e2e-results.json' }],
  ],
  globalSetup: './support/global-setup.ts',
  // Playwright's 5s default is tight for these pages: each one boots React and then fetches its
  // extensions and content before anything is assertable, against a JVM that has just started. The
  // observed spread was 2.6-3.3s on a good run, so 5s left almost no margin and produced exactly the
  // intermittent "element(s) not found" this raises the ceiling on. Assertions still resolve as soon as
  // the condition holds, so a passing run is no slower for it.
  expect: { timeout: 15_000 },
  // Playwright's 30s default is not coherent with the assertion timeout above it: at 15s per wait, a test
  // may afford two slow ones and no more, however fast the work underneath actually is. These specs each
  // sign in, raise a submission through a dialog, open the editor — every one of those a page boot and a
  // round of fetches — and then save several answers, each of which is a workflow event followed by a
  // re-read. Three separate tests reached their last assertion just past 30s once the suite grew, which is
  // the budget being wrong rather than three regressions.
  //
  // Raised rather than worked around per test: `test.slow()` on whichever test failed last is how a suite
  // ends up with the marker on everything and the number still wrong. It stays modest so that a real
  // slowdown still shows up as a failure, and the two stories keep their own `test.slow()` because they
  // are genuinely several times the length of a spec.
  timeout: 60_000,
  use: {
    // Kept on retry and on failure: a trace is the difference between diagnosing a CI-only failure in
    // minutes and not at all
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    ignoreHTTPSErrors: true,
  },
  projects: activeInstances().flatMap(instance =>
    browsers().flatMap(browser => {
      const use = { ...devices[DEVICE_FOR_BROWSER[browser]], baseURL: instance.baseURL };
      const specs = `${instance.name}-${browser}`;
      // Selected by where a test lives rather than by a `testDir` of its own, so that a suite with no
      // stories yet needs no directory created for one.
      return [
        { name: specs, testDir: `./specs/${instance.name}`, testIgnore: '**/stories/**', use },
        {
          name: `${instance.name}-stories-${browser}`,
          testDir: `./specs/${instance.name}`,
          testMatch: '**/stories/**',
          dependencies: [ specs ],
          use,
        },
      ];
    }),
  ),
});
