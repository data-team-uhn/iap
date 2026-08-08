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
 * One Playwright project per launched instance, each pointed at its own instance and reading its specs
 * from its own directory. Anything shared — page objects, fixtures — lives outside those directories and
 * is imported.
 *
 * A suite whose instance was not launched simply produces no project, so `-Dit.platform.skip=true` needs
 * no matching change here.
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
  use: {
    // Kept on retry and on failure: a trace is the difference between diagnosing a CI-only failure in
    // minutes and not at all
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    ignoreHTTPSErrors: true,
  },
  projects: activeInstances().map(instance => ({
    name: instance.name,
    testDir: `./specs/${instance.name}`,
    use: { ...devices['Desktop Chrome'], baseURL: instance.baseURL },
  })),
});
