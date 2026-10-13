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

import { render, screen } from "@testing-library/react";

import ParameterField from "@iap/workflows/ParameterField";
import type { ParameterValue } from "@iap/workflows/parameterValues";

import { parameter } from "./vocabulary.fixture";

const renderField = (dataType: string, values: ParameterValue[]) => render(
  <ParameterField
    parameter={parameter("toState", {
      label: "To state", dataType, parameters: [ parameter("path", { label: "Path" }) ],
    })}
    values={values}
    onChange={vi.fn()}
    onCommit={vi.fn()}
  />
);

describe("ParameterField", () => {
  it("edits a boolean as a choice of yes or no", () => {
    renderField("boolean", [ "true" ]);

    expect(screen.getByRole("combobox")).toHaveTextContent("Yes");
  });

  it("edits a long as a number", () => {
    renderField("long", [ "7" ]);

    expect(screen.getByRole("textbox")).toHaveValue("7");
    expect(screen.getByRole("textbox")).toHaveAttribute("inputmode", "numeric");
  });

  it("edits any other type as text", () => {
    renderField("reference", [ "/a" ]);

    expect(screen.getByRole("textbox")).toHaveValue("/a");
  });

  it("gives a structure's records to the field for structures, and only text to any other", () => {
    const { unmount } = renderField("structure", [ "stray", { path: [ "/a" ] } ]);
    expect(screen.getByRole("group", { name: "To state" })).toBeInTheDocument();
    expect(screen.getByLabelText("Path")).toHaveValue("/a");
    unmount();

    renderField("text", [ { path: [ "/a" ] }, "/b" ]);
    expect(screen.getByRole("textbox")).toHaveValue("/b");
  });
});
