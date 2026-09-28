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

import { SCHEMAS_ROOT, type JcrNode } from "./schemaModel";
import { listing, useNode } from "./useNode";

const asNode = (node: JcrNode): JcrNode => node;

// One schema and its versions. The changes made on its page are workflow events sent by its actions.
export function useSchema(name: string) {
  const path = `${SCHEMAS_ROOT}/${name}`;
  const { value, loading, loadError, reload } = useNode(path, listing(1), asNode);

  return {
    schema: value,
    loading,
    loadError,
    reload,
  };
}
