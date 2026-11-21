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

/** Hands whatever the provider above it gives out to `report`, each time it draws. */
function Probe({ report }: { report: (api: SelectionContextValue) => void }) {
  report(useSelection());
  return null;
}

/** Renders the provider around a probe, and hands back the context plus the change spy. */
function mount(value: string[], { readOnly = false } = {}) {
  const onChange = vi.fn();
  let api!: SelectionContextValue;
  render(
    <SelectionProvider value={value} onChange={onChange} readOnly={readOnly}>
      <Probe report={each => { api = each; }} />
    </SelectionProvider>);
  return { api: () => api, onChange };
}

describe("what has been chosen", () => {
  it("reports the keys it was given", () => {
    const { api } = mount([ "a", "b" ]);

    expect(api().selected.size).toBe(2);
    expect(api().selected.has("a")).toBe(true);
    expect(api().selected.has("c")).toBe(false);
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
});

describe("changes that change nothing", () => {
  it("says nothing when fields are turned on that already are", () => {
    const { api, onChange } = mount([ "a", "b" ]);

    act(() => { api().selectFields([ "a", "b" ]); });

    expect(onChange).not.toHaveBeenCalled();
  });

  it("says nothing when fields are turned off that already are", () => {
    const { api, onChange } = mount([ "a" ]);

    act(() => { api().deselectFields([ "b", "c" ]); });

    expect(onChange).not.toHaveBeenCalled();
  });

  it("says nothing when an empty selection is cleared", () => {
    const { api, onChange } = mount([]);

    act(() => { api().clear(); });

    expect(onChange).not.toHaveBeenCalled();
  });
});

describe("changing several fields at once", () => {
  it("turns on the ones that were off and leaves the rest", () => {
    const { api, onChange } = mount([ "a" ]);

    act(() => { api().selectFields([ "a", "b", "c" ]); });

    expect(onChange).toHaveBeenCalledWith([ "a", "b", "c" ]);
  });

  it("turns off the ones that were on", () => {
    const { api, onChange } = mount([ "a", "b", "c" ]);

    act(() => { api().deselectFields([ "a", "b" ]); });

    expect(onChange).toHaveBeenCalledWith([ "c" ]);
  });

  it("reports one change rather than one per key", () => {
    const { api, onChange } = mount([]);

    act(() => { api().selectFields([ "a", "b", "c" ]); });

    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it("clears what was chosen", () => {
    const { api, onChange } = mount([ "a", "b" ]);

    act(() => { api().clear(); });

    expect(onChange).toHaveBeenCalledWith([]);
  });
});

describe("what a checkbox standing for a group shows", () => {
  it("shows nothing chosen, some chosen, or all", () => {
    expect(checkStateFor(0, 3)).toBe("none");
    expect(checkStateFor(1, 3)).toBe("some");
    expect(checkStateFor(3, 3)).toBe("all");
  });

  it("shows nothing chosen for a group holding nothing", () => {
    expect(checkStateFor(0, 0)).toBe("none");
  });
});

// No control offers a change while the selection is read-only; the provider refuses one anyway
describe("a selection that may not be changed", () => {
  it("still reports what was chosen", () => {
    const { api } = mount([ "a", "b" ], { readOnly: true });

    expect(api().selected.size).toBe(2);
    expect(api().selected.has("a")).toBe(true);
    expect(api().readOnly).toBe(true);
  });

  it("says so, so a control can show that it cannot be used", () => {
    expect(mount([], { readOnly: false }).api().readOnly).toBe(false);
  });

  it("reports no change from any of the four ways to make one", () => {
    const { api, onChange } = mount([ "a" ], { readOnly: true });

    act(() => { api().toggleField("b"); });
    act(() => { api().selectFields([ "c", "d" ]); });
    act(() => { api().deselectFields([ "a" ]); });
    act(() => { api().clear(); });

    expect(onChange).not.toHaveBeenCalled();
  });
});

// Every field row reads these, so a change of identity would draw every row again on every tick
describe("the ways to change a selection, over time", () => {
  function Provided({ value, onChange, probe }: {
    value: string[];
    onChange: (keys: string[]) => void;
    probe: (api: SelectionContextValue) => void;
  }) {
    return (
      <SelectionProvider value={value} onChange={onChange}>
        <Probe report={probe} />
      </SelectionProvider>);
  }

  it("keeps the same functions as the selection and the host change", () => {
    const seen: SelectionContextValue[] = [];
    const { rerender } = render(<Provided value={[ "a" ]} onChange={vi.fn()} probe={api => seen.push(api)} />);

    rerender(<Provided value={[ "a", "b" ]} onChange={vi.fn()} probe={api => seen.push(api)} />);

    const [ before, after ] = [ seen[0], seen[seen.length - 1] ];
    expect(after.selected.size).toBe(2);
    expect(after.toggleField).toBe(before.toggleField);
    expect(after.selectFields).toBe(before.selectFields);
    expect(after.deselectFields).toBe(before.deselectFields);
    expect(after.clear).toBe(before.clear);
  });

  it("still works from the selection and the host as they now are", () => {
    let api!: SelectionContextValue;
    const first = vi.fn();
    const latest = vi.fn();
    const { rerender } = render(<Provided value={[ "a" ]} onChange={first} probe={each => { api = each; }} />);
    rerender(<Provided value={[ "a", "b" ]} onChange={latest} probe={each => { api = each; }} />);

    act(() => { api.toggleField("c"); });

    expect(latest).toHaveBeenCalledWith([ "a", "b", "c" ]);
    expect(first).not.toHaveBeenCalled();
  });
});
