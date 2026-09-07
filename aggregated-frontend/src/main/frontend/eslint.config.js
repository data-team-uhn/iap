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

import js from "@eslint/js";
import stylistic from "@stylistic/eslint-plugin";
import { defineConfig } from "eslint/config";
import importPlugin from "eslint-plugin-import";
import jsxA11y from "eslint-plugin-jsx-a11y";
import react from "eslint-plugin-react";
import reactHooks from "eslint-plugin-react-hooks";
import unusedImports from "eslint-plugin-unused-imports";
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
    pathGroups: [
      {
        pattern: "react",
        group: "external",
        position: "before",
      },
      // Cross-module imports through the @iap/<module> namespace are our own code,
      // sorted between third-party packages and intra-module relative imports
      {
        pattern: "@iap/**",
        group: "internal",
      },
    ],
    pathGroupsExcludedImportTypes: ["react"],
    "newlines-between": "always",
    alphabetize: {
      order: "asc",
      caseInsensitive: true,
    },
  },
];

export default defineConfig([
  {
    ignores: [
      "dist/**",
      "node/**",
      "node_modules/**",
      "webpack.config.js",
      "webpack.config-template.js",
      "vitest.config.ts",
      "vitest.setup.ts",
    ],
  },

  {
    files: ["src/**/*.{ts,tsx}"],

    extends: [
      js.configs.recommended,
      tseslint.configs.strictTypeChecked,
      tseslint.configs.stylisticTypeChecked,
      react.configs.flat.recommended,
      react.configs.flat["jsx-runtime"],
      reactHooks.configs.flat.recommended,
      jsxA11y.flatConfigs.recommended,
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

    settings: {
      react: {
        version: "detect",
      },
    },

    linterOptions: {
      reportUnusedInlineConfigs: "error",
    },

    rules: {
      // TypeScript provides prop validation.
      "react/prop-types": "off",

      // React rules
      "react-hooks/rules-of-hooks": "error",

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
      // Allow void-returning arrow shorthand, e.g. `onClick={() => setOpen(true)}`.
      "@typescript-eslint/no-confusing-void-expression": ["error", { ignoreArrowShorthand: true }],
      // Numbers interpolated into template literals are fine.
      "@typescript-eslint/restrict-template-expressions": ["error", { allowNumber: true }],
      // Same as above, for string + number concatenation (e.g. `"widget-" + index`).
      "@typescript-eslint/restrict-plus-operands": ["error", { allowNumberAndString: true }],

      // Avoid duplicate reports and automatically remove unused imports.
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

      // Import organization.
      "import/order": importOrderRule,

      // Complexity.
      "max-nested-callbacks": ["error", 3],

      // Formatting.
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
    },
  },

  // Test sources are held to the same standard as the rest, minus the rules that only make sense
  // for application code. Everything below is relaxed because the test idiom it forbids is the
  // correct way to write the test, not because the tests were easier to leave failing -- keep it
  // that way, and prefer fixing a test over adding to this list.
  {
    files: ["src/**/*.{test,spec}.{ts,tsx}", "src/**/*.fixture.{ts,tsx}"],

    rules: {
      // Stub collaborators are mostly methods that must exist and do nothing
      "@typescript-eslint/no-empty-function": "off",

      // describe > describe > it > callback is one level past the limit before a test does
      // anything at all
      "max-nested-callbacks": ["error", 5],

      // Passing a spy or a mocked method around by reference is the point of having it
      "@typescript-eslint/unbound-method": "off",

      // An async test body that only makes assertions still has to be async when the helpers it
      // calls are
      "@typescript-eslint/require-await": "off",

      // Rejecting with something that is not an Error is often exactly the case under test
      "@typescript-eslint/prefer-promise-reject-errors": "off",

      // Fixtures stand in for values whose real type is wider than what the test supplies, so
      // stringifying a `RequestInfo | URL` the test itself created is safe
      "@typescript-eslint/no-base-to-string": "off",

      // `vi.mock("x", async importOriginal => ({ ...await importOriginal<typeof import("x")>() }))`
      // is the documented way to partially mock a module, and needs the inline import type
      "@typescript-eslint/consistent-type-imports": [
        "error",
        { prefer: "type-imports", disallowTypeAnnotations: false },
      ],

      // Copying a DOM object to stand in for it (a fake `window.location`, say) is a test-only
      // move that the rule rightly discourages in application code
      "@typescript-eslint/no-misused-spread": "off",

      // Tests deliberately probe for things the types promise are always there -- a jsdom that
      // lacks `matchMedia`, say -- and the guard is what keeps them honest about it
      "@typescript-eslint/no-unnecessary-condition": "off",

      // Reaching into the rendered DOM for something the test just rendered: if it is not there
      // the assertion fails immediately and says so, which is the outcome a test wants anyway --
      // and it beats the `as HTMLElement` this rule otherwise pushes tests towards, which would
      // hide the same mistake behind a wrong type
      "@typescript-eslint/no-non-null-assertion": "off",
    },
  },

  // The catalogue is a self-contained thing: a tree of data, and a panel listing what was picked out
  // of it. It is used inside a submission today, and it is worth being able to show one on its own --
  // an administrator reviewing what a version contains, a researcher looking before they file. What
  // keeps that possible is that it never reaches for a submission, so the boundary is a rule rather
  // than an intention. Everything that joins the two lives beside this directory, not inside it.
  // Nothing in the catalogue may reach another module, at any depth. A sibling inside it is fine --
  // what this stops is a dependency on the submission around it.
  {
    files: [ "src/data-requirement/catalogue/**/*.{ts,tsx}" ],
    // Its own tests are not a dependency on anything: they reach the catalogue the way every suite in
    // this project reaches the module it covers, through the @iap alias
    ignores: [ "src/data-requirement/catalogue/**/*.{test,spec}.{ts,tsx}",
      "src/data-requirement/catalogue/**/fixtures.ts" ],

    rules: {
      "no-restricted-imports": [ "error", {
        patterns: [
          {
            group: [ "@iap/**" ],
            message: "The catalogue may not depend on another module; put the glue beside it.",
          },
        ],
      } ],
    },
  },

  // And it may not climb out of itself by a relative path either. Two blocks because how far "out"
  // is depends on how deep the file sits: one level from the top of the catalogue, two from a
  // subdirectory of it.
  {
    files: [ "src/data-requirement/catalogue/*.{ts,tsx}" ],
    ignores: [ "src/data-requirement/catalogue/*.{test,spec}.{ts,tsx}",
      "src/data-requirement/catalogue/fixtures.ts" ],

    rules: {
      "no-restricted-imports": [ "error", {
        patterns: [
          {
            group: [ "@iap/**", "../*" ],
            message: "The catalogue may not depend on anything outside itself; put the glue beside it.",
          },
        ],
      } ],
    },
  },
  {
    files: [ "src/data-requirement/catalogue/*/*.{ts,tsx}" ],
    ignores: [ "src/data-requirement/catalogue/*/*.{test,spec}.{ts,tsx}" ],

    rules: {
      "no-restricted-imports": [ "error", {
        patterns: [
          {
            group: [ "@iap/**", "../../*" ],
            message: "The catalogue may not depend on anything outside itself; put the glue beside it.",
          },
        ],
      } ],
    },
  },
]);
