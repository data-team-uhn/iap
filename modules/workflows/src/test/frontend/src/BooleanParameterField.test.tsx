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

import { cleanup, screen } from "@testing-library/react";

import { listedOptions } from "@iap/frontend-commons/inputs/valueInput.fixture";
import BooleanParameterField from "@iap/workflows/BooleanParameterField";

import { renderField } from "./parameterField.fixture";

describe("a parameter holding yes or no", () => {
  it("reads a boolean as a word rather than as a tick nobody can move", () => {
    renderField(BooleanParameterField, { dataType: "boolean" }, [ "true" ], { readOnly: true });
    expect(screen.getByText("To state")).toBeInTheDocument();
    expect(screen.getByText("Yes")).toBeInTheDocument();

    cleanup();
    renderField(BooleanParameterField, { dataType: "boolean" }, [ "false" ], { readOnly: true });
    expect(screen.getByText("To state")).toBeInTheDocument();
    expect(screen.getByText("No")).toBeInTheDocument();
  });

  it("reads a word the server ignores as written rather than as no", () => {
    renderField(BooleanParameterField, { dataType: "boolean", maxValues: 0 }, [ "yes", "false" ], { readOnly: true });

    expect(screen.getByText("To state")).toBeInTheDocument();
    expect(screen.getByText("yes, No")).toBeInTheDocument();
  });

  it("flags none of the spellings the server reads as a boolean", () => {
    renderField(BooleanParameterField, { dataType: "boolean", maxValues: 0, description: "Whether to notify" },
      [ "true", "1", "false", "0" ]);

    expect(screen.getByText("Whether to notify")).toBeInTheDocument();
  });

  it("flags a boolean written in capitals, which the server does not read", () => {
    renderField(BooleanParameterField, { dataType: "boolean", maxValues: 0 }, [ "TRUE" ]);

    expect(screen.getByText('"TRUE" is not true or false, so it will be ignored.')).toBeInTheDocument();
  });

  it("shows a word the server ignores as it was written, flagged, and lets a valid one replace it", async () => {
    const { onCommit, user } = renderField(BooleanParameterField, { dataType: "boolean", description: "Whether to notify" },
      [ "yes" ]);

    expect(screen.getByRole("combobox")).toHaveTextContent("yes");
    expect(screen.getByText('"yes" is not true or false, so it will be ignored.')).toBeInTheDocument();
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
    renderField(BooleanParameterField, { dataType: "boolean", maxValues: 0 }, [ "true", "false" ], { readOnly: true });

    expect(screen.getByText("To state")).toBeInTheDocument();
    expect(screen.getByText("Yes, No")).toBeInTheDocument();
  });

  it("takes several values as chips picked from yes and no", async () => {
    const { onCommit, user } = renderField(BooleanParameterField, { dataType: "boolean", maxValues: 0 }, [ "true" ]);

    expect(screen.getByRole("button", { name: "Yes" })).toBeInTheDocument();
    expect(await listedOptions(screen.getByRole("combobox"))).toEqual([ "Yes", "No" ]);
    await user.click(screen.getByRole("option", { name: "No" }));

    expect(onCommit).toHaveBeenCalledWith([ "true", "false" ]);
  });

  it("does not let a word other than yes or no be typed into a list", async () => {
    const { onChange, user } = renderField(BooleanParameterField, { dataType: "boolean", maxValues: 0 });

    await user.type(screen.getByRole("combobox"), "maybe{Enter}");

    expect(onChange).not.toHaveBeenCalled();
  });

  it("marks a word the server ignores in a list, and says when there are too many values", () => {
    renderField(BooleanParameterField, { dataType: "boolean", maxValues: 2 }, [ "true", "maybe", "false" ]);

    expect(screen.getByText('"maybe" is not true or false, so it will be ignored. At most 2 values are accepted.'))
      .toBeInTheDocument();
  });

  it("keeps flagging a required value that is only a word the server ignores once it has been left", async () => {
    const { user } = renderField(BooleanParameterField, { dataType: "boolean", minValues: 1 }, [ "maybe" ]);
    expect(screen.getByText('"maybe" is not true or false, so it will be ignored.')).toBeInTheDocument();

    await user.click(screen.getByRole("combobox"));
    await user.keyboard("{Escape}");
    await user.tab();

    expect(screen.getByText('"maybe" is not true or false, so it will be ignored.')).toBeInTheDocument();
  });

  it("does not ask for a required value while its menu is open", async () => {
    const { user } = renderField(BooleanParameterField, { dataType: "boolean", minValues: 1 });

    await user.click(screen.getByRole("combobox"));

    expect(screen.getByRole("listbox")).toBeInTheDocument();
    expect(screen.queryByText("A value is required.")).not.toBeInTheDocument();
  });

  it("asks for nothing when it is optional and left unset", async () => {
    const { user } = renderField(BooleanParameterField, { dataType: "boolean" });

    await user.click(screen.getByRole("combobox"));
    await user.keyboard("{Escape}");
    await user.tab();

    expect(screen.queryByText("A value is required.")).not.toBeInTheDocument();
  });
});
