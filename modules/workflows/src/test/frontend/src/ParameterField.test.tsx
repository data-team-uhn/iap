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

import { screen } from "@testing-library/react";

import ParameterField from "@iap/workflows/ParameterField";
import type { ParameterValue } from "@iap/workflows/parameterValues";

import { renderField } from "./parameterField.fixture";
import { parameter } from "./vocabulary.fixture";

const renderOf = (dataType: string, values: ParameterValue[]) =>
  renderField<ParameterValue>(ParameterField, { dataType, parameters: [ parameter("path", { label: "Path" }) ] }, values);

describe("ParameterField", () => {
  it("edits a boolean as a choice of yes or no", () => {
    renderOf("boolean", [ "true" ]);

    expect(screen.getByRole("combobox")).toHaveTextContent("Yes");
  });

  it("edits a long as a number", () => {
    renderOf("long", [ "7" ]);

    expect(screen.getByRole("textbox")).toHaveValue("7");
    expect(screen.getByRole("textbox")).toHaveAttribute("inputmode", "numeric");
  });

  it("edits any other type as text", () => {
    renderOf("reference", [ "/a" ]);

    expect(screen.getByRole("textbox")).toHaveValue("/a");
  });

  it("gives a structure's records to the field for structures", () => {
    renderOf("structure", [ "stray", { path: [ "/a" ] } ]);

    expect(screen.getByRole("group", { name: "To state" })).toBeInTheDocument();
    expect(screen.getByLabelText("Path")).toHaveValue("/a");
  });

  it("gives only text to a field that is not for structures", () => {
    renderOf("text", [ { path: [ "/a" ] }, "/b" ]);

    expect(screen.getByRole("textbox")).toHaveValue("/b");
  });
});
