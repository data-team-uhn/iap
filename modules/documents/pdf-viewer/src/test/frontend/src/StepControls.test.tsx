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
// Tests of the controls stepping through a list. The hidden texts that hold their width are found by their
// `data-size` attribute, since they are not in the page's text.
import { useState } from "react";

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import StepControls from "@iap/documents-pdf-viewer/StepControls";

describe("StepControls", () => {
  it("steps either way, as far as the ends", async () => {
    const onStep = vi.fn();
    render(<StepControls position={1} count={3} itemName="change" onStep={onStep} />);

    expect(screen.getByText("1 of 3")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous change" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Next change" }));
    expect(onStep).toHaveBeenCalledWith(2, "next");
  });

  it("names its buttons in tooltips, the one disabled at an end too", async () => {
    render(<StepControls position={1} count={3} itemName="change" onStep={vi.fn()} />);

    await userEvent.hover(screen.getByRole("button", { name: "Previous change" }).closest("span") as HTMLElement);

    expect(await screen.findByRole("tooltip")).toHaveTextContent("Previous change");
  });

  it("keeps the focus on the button pressed, and gives it to the other one at the end", async () => {
    render(<Stepping count={3} />);
    const next = screen.getByRole("button", { name: "Next change" });

    await userEvent.click(next);
    expect(next).toHaveFocus();

    await userEvent.click(next);
    expect(screen.getByRole("button", { name: "Previous change" })).toHaveFocus();
  });

  it("gives the focus to the button named as it is drawn, or to the other one at its end", () => {
    const { unmount } = render(<StepControls position={2} count={3} itemName="change" focused="next" onStep={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Next change" })).toHaveFocus();
    unmount();

    render(<StepControls position={3} count={3} itemName="change" focused="next" onStep={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Previous change" })).toHaveFocus();
  });

  it("takes no focus when drawn without being stepped to", () => {
    render(<StepControls position={2} count={3} itemName="change" onStep={vi.fn()} />);

    expect(document.body).toHaveFocus();
  });

  it("keeps room for the widest text it can show, out of the page's text", () => {
    const { container } = render(
      <StepControls position={1} count={12} itemName="change" heading="12 changes" widest={[ "Change 12 of 12" ]} onStep={vi.fn()} />,
    );

    expect(screen.getByText("12 changes")).toBeInTheDocument();
    expect(screen.queryByText("Change 12 of 12")).not.toBeInTheDocument();
    expect(container.querySelector("[data-size='Change 12 of 12']")).not.toBeNull();
    expect(container.querySelector("[data-size='12 of 12']")).toBeNull();
  });

  it("keeps room for each widest text once, though given twice", () => {
    const { container } = render(
      <StepControls position={1} count={2} itemName="change" heading="2 changes" widest={[ "Note 1 of 1", "Note 1 of 1" ]} onStep={vi.fn()} />,
    );

    expect(container.querySelectorAll("[data-size='Note 1 of 1']")).toHaveLength(1);
  });

  it("names each button after the item it goes to, where they are of more than one sort", () => {
    render(<StepControls position={2} count={3} itemName={{ previous: "note", next: "change" }} onStep={vi.fn()} />);

    expect(screen.getByRole("button", { name: "Previous note" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Next change" })).toBeEnabled();
  });

  it("keeps room for the count at its last position", () => {
    const { container } = render(<StepControls position={2} count={12} itemName="passage" onStep={vi.fn()} />);

    expect(container.querySelector("[data-size='12 of 12']")).not.toBeNull();
  });
});

// Controls that move to the position each of their steps asks for, as a list's controls do.
function Stepping({ count }: { count: number }) {
  const [ position, setPosition ] = useState(1);
  return <StepControls position={position} count={count} itemName="change" onStep={setPosition} />;
}
