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

/*
 * The end-to-end sources are held to the same standard as the application frontend, so this is the
 * frontend's eslint.config.js with the parts that only exist for React removed: there is no JSX here,
 * no component to check hooks or accessibility on, and no "@iap/<module>" namespace to sort imports by.
 * The rule set that remains -- strict type-aware TypeScript, import order, unused imports, formatting --
 * is deliberately identical, down to the options, so that a rule tightened over there is worth tightening
 * here too.
 *
 * It is a separate configuration rather than a shared one because the two packages have separate
 * dependency trees: this one is installed on its own, without the frontend's plugins, and reaching across
 * to the frontend's node_modules would tie a test package to the build order of an unrelated module.
 *
 * Named .mjs, where the frontend's is .js, because this package is not declared "type": "module" -- and
 * should not be, since that is also how Playwright decides whether to load the specs as ES modules.
 */

import js from "@eslint/js";
import stylistic from "@stylistic/eslint-plugin";
import { defineConfig } from "eslint/config";
import importPlugin from "eslint-plugin-import";
import unusedImports from "eslint-plugin-unused-imports";
import globals from "globals";
import tseslint from "typescript-eslint";

const importOrderRule = [
  "error",
  {
    groups: [
      "builtin",
      "external",
      "internal",
      ["parent", "sibling", "index"],
      "type",
    ],
    "newlines-between": "always",
    alphabetize: {
      order: "asc",
      caseInsensitive: true,
    },
  },
];

// Formatting, shared by the TypeScript sources and the plain-Node script, since neither has a compiler
// step that would reformat them.
const formattingRules = {
  "@stylistic/indent": ["error", 2, { SwitchCase: 1 }],
  "@stylistic/max-len": [
    "error",
    {
      code: 120,
      ignoreUrls: true,
      ignoreStrings: true,
      ignoreComments: true,
      ignoreTemplateLiterals: true,
    },
  ],
  "@stylistic/no-extra-semi": "error",
  "@stylistic/no-mixed-spaces-and-tabs": ["error", "smart-tabs"],
  "@stylistic/no-tabs": "error",
  "@stylistic/no-trailing-spaces": "error",
  "@stylistic/object-curly-spacing": ["error", "always"],
  "@stylistic/eol-last": ["error", "always"],
  "@stylistic/linebreak-style": "off",
};

// Reported as errors rather than left to the compiler, and removable with --fix.
const unusedImportRules = {
  "no-unused-vars": "off",
  "@typescript-eslint/no-unused-vars": "off",
  "unused-imports/no-unused-imports": "error",
  "unused-imports/no-unused-vars": [
    "error",
    {
      vars: "all",
      varsIgnorePattern: "^_",
      args: "after-used",
      argsIgnorePattern: "^_",
      caughtErrors: "all",
      caughtErrorsIgnorePattern: "^_",
      ignoreRestSiblings: true,
    },
  ],
};

export default defineConfig([
  {
    ignores: [
      "node_modules/**",
      // Everything Playwright writes: results, the HTML report, and the blobs behind it
      "test-results/**",
      "playwright-report/**",
      "blob-report/**",
      ".playwright/**",
    ],
  },

  {
    files: ["**/*.ts"],

    extends: [
      js.configs.recommended,
      tseslint.configs.strictTypeChecked,
      tseslint.configs.stylisticTypeChecked,
    ],

    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },

    plugins: {
      "@stylistic": stylistic,
      import: importPlugin,
      "unused-imports": unusedImports,
    },

    linterOptions: {
      reportUnusedInlineConfigs: "error",
    },

    rules: {
      // Keep imports and type-only imports consistent.
      "@typescript-eslint/consistent-type-imports": [
        "error",
        {
          prefer: "type-imports",
          fixStyle: "inline-type-imports",
        },
      ],
      "@typescript-eslint/consistent-type-exports": [
        "error",
        {
          fixMixedExportsWithInlineTypeSpecifier: true,
        },
      ],
      "@typescript-eslint/switch-exhaustiveness-check": "error",

      // Rule adjustments for this codebase.
      "@typescript-eslint/no-confusing-void-expression": ["error", { ignoreArrowShorthand: true }],
      "@typescript-eslint/restrict-template-expressions": ["error", { allowNumber: true }],
      "@typescript-eslint/restrict-plus-operands": ["error", { allowNumberAndString: true }],

      ...unusedImportRules,

      // Import organization.
      "import/order": importOrderRule,

      // Complexity.
      "max-nested-callbacks": ["error", 3],

      ...formattingRules,
    },
  },

  // Specs are held to the same standard as the rest, minus the rules that only make sense outside a
  // test. Everything below is relaxed because the idiom it forbids is the correct way to write the
  // test, not because the tests were easier to leave failing -- keep it that way, and prefer fixing a
  // test over adding to this list.
  {
    files: ["specs/**/*.spec.ts"],

    rules: {
      // `test.describe > test > test.step > callback` is one level past the limit before a test has
      // asserted anything
      "max-nested-callbacks": ["error", 5],
    },
  },

  // The one script that runs under bare Node rather than through Playwright's transpiler, so it is
  // JavaScript and outside the TypeScript project: linted without the type-aware rules, which have no
  // type information to work from here.
  {
    files: ["**/*.mjs"],

    extends: [js.configs.recommended],

    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      // nodeBuiltin rather than node: this is an ES module, so the CommonJS-only globals
      // (`require`, `module`, `__dirname`) are genuinely absent and should still be reported.
      globals: globals.nodeBuiltin,
    },

    plugins: {
      "@stylistic": stylistic,
      import: importPlugin,
      "unused-imports": unusedImports,
    },

    linterOptions: {
      reportUnusedInlineConfigs: "error",
    },

    rules: {
      ...unusedImportRules,
      "import/order": importOrderRule,
      "max-nested-callbacks": ["error", 3],
      ...formattingRules,
    },
  },
]);
