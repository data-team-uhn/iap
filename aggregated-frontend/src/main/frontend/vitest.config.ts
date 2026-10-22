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

import { globSync, readFileSync } from "node:fs";
import path from "node:path";

import { defineConfig } from "vitest/config";

const TESTS = "src/**/*.{test,spec}.{ts,tsx}";

// A file that replaces a module with vi.mock gets a process of its own. In a process shared with other files,
// whether a file sees the mock or the real module depends on which files ran before it.
const MOCKS_A_MODULE = /\bvi\.(mock|doMock)\(/;
const mockingFiles = globSync(TESTS, { cwd: import.meta.dirname })
  .filter(file => MOCKS_A_MODULE.test(readFileSync(path.join(import.meta.dirname, file), "utf8")));

// Tests are authored co-located with the components in each module's
// src/main/frontend/src/ tree, and run here in the aggregated frontend after the
// `aggregate` step copies every module's sources (and their *.test.* files) into
// ./src/<module>/. Running here means a single node_modules resolves shared deps
// (@mui, react, ...), which is the whole point of the aggregated bundle. See CLAUDE.md.
export default defineConfig({
  // React 19 automatic JSX runtime, transformed by esbuild (no Babel needed for tests).
  esbuild: {
    jsx: "automatic",
  },
  resolve: {
    // Cross-module imports use the @iap/<module>/... namespace, same mapping as webpack's
    alias: {
      "@iap": path.resolve(import.meta.dirname, "src"),
    },
  },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: ["./vitest.setup.ts"],
    // Every file that can shares a process to minimize third party library loads
    projects: [
      {
        extends: true,
        test: { name: "isolated", include: mockingFiles },
      },
      {
        extends: true,
        test: {
          name: "shared",
          include: [TESTS],
          exclude: mockingFiles,
          isolate: false,
          setupFiles: ["./vitest.shared.setup.ts"],
        },
      },
    ],
    // Sized for the Maven build rather than for a quiet laptop: the heaviest suites here mount MUI
    // dialogs or pull in bpmn-js under jsdom, and several already take seconds unloaded.
    testTimeout: 30000,
    // Vitest sizes its pool to the machine, but a fork per core - each with its own jsdom, React and
    // MUI - oversubscribes both cores and memory during a build, and the slowdown lands on
    // individual tests as timeouts rather than as an honestly slower run. Half the cores is enough
    // to keep the suite parallel without the whole machine thrashing.
    maxWorkers: "50%",
    server: {
      deps: {
        // MUI X packages import their own stylesheets from their ESM builds; inlining them lets
        // Vite transform those imports (Node itself cannot load .css modules)
        inline: [/@mui\/x-data-grid/],
      },
    },
    coverage: {
      provider: "v8",
      include: ["src/**/*.{js,jsx,ts,tsx}"],
      // *.fixture.* are stand-ins a test loads as if it were application code (see
      // remoteAsset.fixture.tsx, imported through assetManager's real dynamic import); they are
      // test scaffolding rather than something whose own coverage means anything.
      exclude: ["src/**/*.{test,spec}.{ts,tsx}", "src/**/*.fixture.{ts,tsx}"],
      // Under the Maven module's target directory, so the report is cleaned with the build
      // and stays out of the license-header check's way
      reportsDirectory: "../../../target/coverage",
      // The frontend counterpart of the Java build's jacoco check: `pnpm test` runs with
      // --coverage, and dropping below these floors fails the Maven build. The values track
      // the current overall coverage (ratchet: raise them as coverage grows, never lower
      // them without a reason stated in the commit). They sit a little under the real figures
      // so that an unrelated change does not have to move them; new code is expected to arrive
      // with its tests, not to spend the slack.
      thresholds: {
        statements: 97,
        branches: 97,
        functions: 97,
        lines: 97,
      },
    },
  },
});
