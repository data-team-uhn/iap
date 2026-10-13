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

import BooleanParameterField from "@iap/workflows/BooleanParameterField";

import { renderField } from "./parameterField.fixture";

describe("a parameter holding yes or no", () => {
  it("reads a boolean as a word rather than as a tick nobody can move", () => {
    renderField(BooleanParameterField, { dataType: "boolean" }, [ "true" ], true);
    expect(screen.getByText("To state: Yes")).toBeInTheDocument();

    renderField(BooleanParameterField, { dataType: "boolean" }, [ "false" ], true);
    expect(screen.getByText("To state: No")).toBeInTheDocument();
  });

  it("reads a yes the same whatever case and spacing the diagram wrote it in", () => {
    // The server accepts the word in any case, so a hand-authored diagram saying TRUE means yes here
    renderField(BooleanParameterField, { dataType: "boolean" }, [ " TRUE " ], true);

    expect(screen.getByText("To state: Yes")).toBeInTheDocument();
  });

  it("reads a word the server ignores as written rather than as no", () => {
    renderField(BooleanParameterField, { dataType: "boolean", maxValues: 0 }, [ "yes", "FALSE" ], true);

    expect(screen.getByText("To state: yes, No")).toBeInTheDocument();
  });

  it("shows a word the server ignores as no choice, and asks for one", async () => {
    const { onCommit, user } = renderField(BooleanParameterField, { dataType: "boolean", description: "Whether to notify" },
      [ "yes" ]);

    expect(screen.getByRole("combobox")).not.toHaveTextContent(/Yes|No/);
    expect(screen.getByText('"yes" is not true or false, so it will be ignored')).toBeInTheDocument();
    expect(screen.queryByText("Whether to notify")).not.toBeInTheDocument();
    await user.click(screen.getByRole("combobox"));
    await user.click(screen.getByRole("option", { name: "No" }));

    expect(onCommit).toHaveBeenCalledWith([ "false" ]);
  });

  it("offers yes and no, reporting either", async () => {
    const { onChange, onCommit, user } = renderField(BooleanParameterField, { dataType: "boolean", description: "Whether to notify" });

    expect(screen.getByText("Whether to notify")).toBeInTheDocument();
    await user.click(screen.getByRole("combobox"));
    await user.click(screen.getByRole("option", { name: "Yes" }));

    expect(onChange).toHaveBeenCalledWith([ "true" ]);
    expect(onCommit).toHaveBeenCalledWith([ "true" ]);
  });

  it("shows a yes written in another case as yes", () => {
    renderField(BooleanParameterField, { dataType: "boolean" }, [ "True" ]);

    expect(screen.getByRole("combobox")).toHaveTextContent("Yes");
  });

  it("reports no as no rather than as nothing", async () => {
    const { onChange, user } = renderField(BooleanParameterField, { dataType: "boolean", minValues: 1 }, [ "true" ]);

    await user.click(screen.getByRole("combobox"));
    await user.click(screen.getByRole("option", { name: "No" }));

    expect(onChange).toHaveBeenCalledWith([ "false" ]);
  });

  it("can be left unset when it is optional, so the node type's default stands", async () => {
    const { onChange, user } = renderField(BooleanParameterField, { dataType: "boolean" }, [ "false" ]);

    await user.click(screen.getByRole("combobox"));
    await user.click(screen.getByRole("option", { name: "None" }));

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("cannot be left unset when it is required", async () => {
    const { user } = renderField(BooleanParameterField, { dataType: "boolean", minValues: 1 }, [ "false" ]);

    await user.click(screen.getByRole("combobox"));

    expect(screen.queryByRole("option", { name: "None" })).not.toBeInTheDocument();
  });

  it("reads several values each as a word", () => {
    renderField(BooleanParameterField, { dataType: "boolean", maxValues: 0 }, [ "true", "false" ], true);

    expect(screen.getByText("To state: Yes, No")).toBeInTheDocument();
  });

  it("takes several values as chips, the way text does", () => {
    renderField(BooleanParameterField, { dataType: "boolean", maxValues: 0 }, [ "true" ]);

    expect(screen.getByRole("button", { name: "true" })).toBeInTheDocument();
  });
});
