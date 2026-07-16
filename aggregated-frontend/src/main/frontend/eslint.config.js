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
      // Let's see if we can survive with those rules on
      //"react-hooks/exhaustive-deps": "off",
      //"react-hooks/set-state-in-effect": "off",
      //"react-hooks/refs": "off",
      //"react-hooks/preserve-manual-memoization": "off",
      //"jsx-a11y/no-autofocus": "off",

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
]);
