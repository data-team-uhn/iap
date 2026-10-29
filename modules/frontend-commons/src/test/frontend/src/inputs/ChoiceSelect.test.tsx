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

import { fireEvent, render, screen, within } from "@testing-library/react";

import ChoiceSelect from "@iap/frontend-commons/inputs/ChoiceSelect";
import { REQUIRED_PROBLEM } from "@iap/frontend-commons/inputs/valueInput";

const CHOICES = [ { value: "list", label: "List" }, { value: "grid", label: "Grid" } ];

const renderSelect = (props: Partial<Parameters<typeof ChoiceSelect>[0]> = {}) => {
  const onChange = vi.fn();
  const onCommit = vi.fn();
  render(<ChoiceSelect label="Shown as" choices={CHOICES} values={[ "list" ]} onChange={onChange}
    onCommit={onCommit} {...props} />);
  return { onChange, onCommit };
};

const select = () => screen.getByRole("combobox", { name: /Shown as/ });

const options = async () => {
  fireEvent.mouseDown(select());
  return within(await screen.findByRole("listbox")).getAllByRole("option").map(option => option.textContent);
};

describe("ChoiceSelect", () => {
  it("reports a pick as finished", async () => {
    const { onChange, onCommit } = renderSelect();

    fireEvent.mouseDown(select());
    fireEvent.click(await screen.findByRole("option", { name: "Grid" }));

    expect(onChange).toHaveBeenCalledWith([ "grid" ]);
    expect(onCommit).toHaveBeenCalledWith([ "grid" ]);
  });

  it("offers None when optional, and reports it as no value", async () => {
    const { onChange } = renderSelect();

    expect(await options()).toEqual([ "None", "List", "Grid" ]);
    fireEvent.click(screen.getByRole("option", { name: "None" }));
    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("offers no None when required, unless asked to", async () => {
    renderSelect({ required: true });
    expect(await options()).toEqual([ "List", "Grid" ]);
  });

  it("offers None when required, if asked to", async () => {
    renderSelect({ required: true, allowNone: true });
    expect(await options()).toEqual([ "None", "List", "Grid" ]);
  });

  it("keeps a value no longer among the choices", async () => {
    renderSelect({ values: [ "matrix" ] });

    expect(select()).toHaveTextContent("matrix");
    expect(await options()).toEqual([ "None", "List", "Grid", "matrix" ]);
  });

  it("opens as soon as it appears when asked to", async () => {
    renderSelect({ openOnMount: true });
    expect(await screen.findByRole("listbox")).toBeInTheDocument();
  });

  it("flags a missing value once left", () => {
    renderSelect({ values: [], required: true });

    fireEvent.blur(select());

    expect(screen.getByText(REQUIRED_PROBLEM)).toBeInTheDocument();
  });

  it("counts an empty choice as missing, and a picked one as not", () => {
    const { unmount } = render(<ChoiceSelect required label="Shown as" choices={CHOICES} values={[ "" ]}
      onChange={vi.fn()} />);
    fireEvent.blur(select());
    expect(screen.getByText(REQUIRED_PROBLEM)).toBeInTheDocument();
    unmount();

    renderSelect({ required: true });
    fireEvent.blur(select());
    expect(screen.queryByText(REQUIRED_PROBLEM)).not.toBeInTheDocument();
  });

  it("shows the label of its value when read-only", () => {
    renderSelect({ readOnly: true });

    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.getByText("Shown as: List")).toBeInTheDocument();
  });
});
