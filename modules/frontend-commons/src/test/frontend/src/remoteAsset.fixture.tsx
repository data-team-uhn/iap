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

// Stands in for a component published as an asset, so that assetManager's tests run the real
// dynamic import. Only the tests load it, by its URL.

export default function RemoteDefault({ label }: { label?: string }) {
  return <div data-testid="remote-default">{label ?? "default export"}</div>;
}

export function RemoteNamed({ label }: { label?: string }) {
  return <div data-testid="remote-named">{label ?? "named export"}</div>;
}
