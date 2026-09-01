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

import { act, render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import {
  checkStateFor,
  type SelectionContextValue,
} from "@iap/data-requirement/catalogue/selectionContext";
import { SelectionProvider } from "@iap/data-requirement/catalogue/SelectionProvider";
import { useSelection } from "@iap/data-requirement/catalogue/useSelection";

/** Renders the provider around a probe, and hands back the context plus the change spy. */
function mount(value: string[]) {
  const onChange = vi.fn();
  let api!: SelectionContextValue;
  function Probe() {
    api = useSelection();
    return null;
  }
  render(
    <SelectionProvider value={value} onChange={onChange}>
      <Probe />
    </SelectionProvider>);
  return { api: () => api, onChange };
}

describe("what has been chosen", () => {
  it("reports the keys it was given", () => {
    const { api } = mount([ "a", "b" ]);

    expect(api().count).toBe(2);
    expect(api().isSelected("a")).toBe(true);
    expect(api().isSelected("c")).toBe(false);
  });

  it("turns a field on", () => {
    const { api, onChange } = mount([ "a" ]);

    act(() => { api().toggleField("b"); });

    expect(onChange).toHaveBeenCalledWith([ "a", "b" ]);
  });

  it("turns a field off", () => {
    const { api, onChange } = mount([ "a", "b" ]);

    act(() => { api().toggleField("a"); });

    expect(onChange).toHaveBeenCalledWith([ "b" ]);
  });

  it("forces a field on or off when told which", () => {
    const { api, onChange } = mount([]);

    act(() => { api().toggleField("a", true); });

    expect(onChange).toHaveBeenCalledWith([ "a" ]);
  });
});

// Every change is a save the host has to make, and a save is a round trip that can fail
describe("changes that change nothing", () => {
  it("says nothing when a field is turned on that already is", () => {
    const { api, onChange } = mount([ "a" ]);

    act(() => { api().toggleField("a", true); });

    expect(onChange).not.toHaveBeenCalled();
  });

  it("says nothing when a field is turned off that already is", () => {
    const { api, onChange } = mount([]);

    act(() => { api().toggleField("a", false); });

    expect(onChange).not.toHaveBeenCalled();
  });

  it("says nothing when a whole group is turned on that already is", () => {
    const { api, onChange } = mount([ "a", "b" ]);

    act(() => { api().setMany([ "a", "b" ], true); });

    expect(onChange).not.toHaveBeenCalled();
  });

  it("says nothing when the replacement is the selection it already holds", () => {
    const { api, onChange } = mount([ "a", "b" ]);

    act(() => { api().replace([ "b", "a" ]); });

    expect(onChange).not.toHaveBeenCalled();
  });

  it("says nothing when an empty selection is cleared", () => {
    const { api, onChange } = mount([]);

    act(() => { api().clear(); });

    expect(onChange).not.toHaveBeenCalled();
  });
});

describe("changing a whole group at once", () => {
  it("turns on the ones that were off and leaves the rest", () => {
    const { api, onChange } = mount([ "a" ]);

    act(() => { api().setMany([ "a", "b", "c" ], true); });

    expect(onChange).toHaveBeenCalledWith([ "a", "b", "c" ]);
  });

  it("turns off the ones that were on", () => {
    const { api, onChange } = mount([ "a", "b", "c" ]);

    act(() => { api().setMany([ "a", "b" ], false); });

    expect(onChange).toHaveBeenCalledWith([ "c" ]);
  });

  it("reports one change rather than one per key", () => {
    const { api, onChange } = mount([]);

    act(() => { api().setMany([ "a", "b", "c" ], true); });

    expect(onChange).toHaveBeenCalledTimes(1);
  });
});

describe("swapping the selection", () => {
  it("takes a different one", () => {
    const { api, onChange } = mount([ "a" ]);

    act(() => { api().replace([ "b", "c" ]); });

    expect(onChange).toHaveBeenCalledWith([ "b", "c" ]);
  });

  it("takes an empty one, which is a real answer", () => {
    const { api, onChange } = mount([ "a" ]);

    act(() => { api().replace([]); });

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("clears what was chosen", () => {
    const { api, onChange } = mount([ "a", "b" ]);

    act(() => { api().clear(); });

    expect(onChange).toHaveBeenCalledWith([]);
  });

  // Two selections of the same size that share no keys
  it("notices a replacement of the same size that is not the same selection", () => {
    const { api, onChange } = mount([ "a", "b" ]);

    act(() => { api().replace([ "c", "d" ]); });

    expect(onChange).toHaveBeenCalledWith([ "c", "d" ]);
  });
});

describe("what a checkbox standing for a group shows", () => {
  it("shows nothing chosen, some chosen, or all", () => {
    expect(checkStateFor(0, 3)).toBe("none");
    expect(checkStateFor(1, 3)).toBe("some");
    expect(checkStateFor(3, 3)).toBe("all");
  });

  // An empty group is not a group with everything chosen
  it("shows nothing chosen for a group holding nothing", () => {
    expect(checkStateFor(0, 0)).toBe("none");
  });
});
