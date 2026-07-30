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

import path from "node:path";

import { defineConfig } from "vitest/config";

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
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    coverage: {
      provider: "v8",
      include: ["src/**/*.{js,jsx,ts,tsx}"],
      exclude: ["src/**/*.{test,spec}.{ts,tsx}"],
      // Under the Maven module's target directory, so the report is cleaned with the build
      // and stays out of the license-header check's way
      reportsDirectory: "../../../target/coverage",
      // The frontend counterpart of the Java build's jacoco check: `yarn test` runs with
      // --coverage, and dropping below these floors fails the Maven build. The values track
      // the current overall coverage (ratchet: raise them as coverage grows, never lower
      // them without a reason stated in the commit). The floors are low because several older
      // files (the BPMN editor, dialogs, demo widgets) predate testing and count in full; as
      // they gain tests, raise them.
      thresholds: {
        statements: 52,
        branches: 51,
        functions: 54,
        lines: 53,
      },
    },
  },
});
