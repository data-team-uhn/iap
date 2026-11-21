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

import { vi } from "vitest";

/** A ResizeObserver a test reports sizes through. The one created last is `latest`. */
export class FakeResizeObserver {
  static latest: FakeResizeObserver | undefined;
  readonly disconnect = vi.fn();
  constructor(private readonly callback: ResizeObserverCallback) {
    FakeResizeObserver.latest = this;
  }
  observe() {
    // Sizes arrive through `resize`
  }
  resize(width: number) {
    this.callback([ { contentRect: { width } } as ResizeObserverEntry ], this as unknown as ResizeObserver);
  }
}

/** Lays out every element a component measures at the given width, as soon as it is observed. */
export function stubElementWidth(width: number) {
  vi.stubGlobal("ResizeObserver", class extends FakeResizeObserver {
    override observe() {
      this.resize(width);
    }
  });
}
