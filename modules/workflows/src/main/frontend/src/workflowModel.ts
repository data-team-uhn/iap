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

// The workflow definitions' home in the repository, and the parsing of its JSON listing —
// shared by the BPMN editor and the administration console widget.

export const WORKFLOWS_ROOT = "/Workflows";

// A node parsed from the repository's JSON serialization: a known primary type, everything else
// read defensively.
export type JcrNode = {
  "jcr:primaryType"?: string;
} & Record<string, unknown>;

// One version of a workflow definition, flattened for listing. The diagram itself is deliberately
// absent: it is an nt:file child of the version node, fetched on its own path only when something
// is about to render it.
export interface WorkflowVersionSummary {
  name: string;
  path: string;
  title: string;
  version: string;
  description: string;
}

function extractVersions(defKey: string, defNode: JcrNode): WorkflowVersionSummary[] {
  return Object.entries(defNode)
    .filter(([, v]) => v && typeof v === "object" && (v as JcrNode)["jcr:primaryType"] === "wf:WorkflowVersion")
    .map(([versionKey, versionNode]) => {
      const version = versionNode as JcrNode;
      return {
        name: `${defKey}/${versionKey}`,
        path: `${WORKFLOWS_ROOT}/${defKey}/${versionKey}`,
        title: (defNode.title as string) || defKey,
        version: (version.version as string) || "",
        description: (version.description as string) || "",
      };
    });
}

// Flattens the parsed listing of the workflows homepage into one summary per workflow version.
// A value is untrusted parsed JSON (e.g. a dangling/null JCR entry), not actually guaranteed to
// be a JcrNode - typeof null === "object", so the truthy check is required, not redundant.
export function parseWorkflowList(data: Record<string, unknown>): WorkflowVersionSummary[] {
  return Object.entries(data)
    .filter(([, v]) => v && typeof v === "object" && (v as JcrNode)["jcr:primaryType"] === "wf:WorkflowDefinition")
    .flatMap(([defKey, defNode]) => extractVersions(defKey, defNode as JcrNode));
}
