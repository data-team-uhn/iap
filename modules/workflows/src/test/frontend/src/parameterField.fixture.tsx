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


import type { ComponentType, ReactNode } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { render } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { appTheme } from "@iap/frontend-commons/appTheme";
import type { ParameterFieldProps } from "@iap/workflows/parameterWidget";
import type { Parameter } from "@iap/workflows/workflowTypes";

import { parameter } from "./vocabulary.fixture";

export const STATES = [ { value: "DRAFT", label: "Draft" }, { value: "ACTIVE", label: "Active" } ];

// The theme the editor renders in, as a `wrapper` for `render`, so that a `rerender` keeps it.
export const Themed = ({ children }: { children: ReactNode }) =>
  <ThemeProvider theme={appTheme} defaultMode="light">{children}</ThemeProvider>;

// Renders one parameter field the way the properties panel does, and hands back what it reports. The
// parameter is a text one named `toState` in whatever the test does not say.
export const renderField = <Value = string>(
  Field: ComponentType<ParameterFieldProps<Value>>,
  given: Partial<Parameter>,
  values: Value[] = [],
  readOnly = false,
  commaSeparated = false
) => {
  const onChange = vi.fn<(values: Value[]) => void>();
  const onCommit = vi.fn<(values: Value[]) => void>();
  render(
    <Field
      parameter={parameter("toState", { label: "To state", ...given })}
      values={values}
      onChange={onChange}
      onCommit={onCommit}
      commaSeparated={commaSeparated}
      readOnly={readOnly}
    />,
    { wrapper: Themed }
  );
  return { onChange, onCommit, user: userEvent.setup() };
};
