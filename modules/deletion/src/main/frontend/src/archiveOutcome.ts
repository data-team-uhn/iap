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

import type { ActionResponse } from "./archiveApi";

/** What to tell the user after a restore or a purge, and how loudly. */
export interface Outcome {
  severity: "success" | "warning" | "error";
  message: string;
}

/**
 * Turns an endpoint answer into something worth reading.
 *
 * The endpoints already explain themselves in `status.message` whenever there is anything to
 * explain, so this prefers what the server said and only supplies wording where it said nothing —
 * a refusal reported by the server in its own words beats a sentence invented here that might not
 * describe the actual reason.
 */
export function describeOutcome(response: ActionResponse): Outcome {
  const explanation = response["status.message"];
  switch (response.status) {
    case "restored": {
      const count = response.restored?.length ?? 0;
      return {
        severity: "success",
        message: count === 1
          ? "Restored 1 item to where it was deleted from."
          : `Restored ${String(count)} items to where they were deleted from.`,
      };
    }
    case "deleted":
      return { severity: "success", message: "The entry and everything archived in it were permanently removed." };
    case "conflict": {
      const conflicts = response.conflicts ?? [];
      const detail = conflicts
        .map(conflict => `${conflict.originalPath} (${conflict.reason})`)
        .join(", ");
      return {
        severity: "warning",
        message: detail
          ? `Nothing was restored, because something is in the way: ${detail}`
          : explanation ?? "Nothing was restored, because something is in the way.",
      };
    }
    case "vetoed":
      return {
        severity: "warning",
        message: explanation ?? "A guard refused to destroy this entry.",
      };
    case "invalid":
      return { severity: "error", message: explanation ?? "That entry cannot be acted on." };
    case "failed":
      return { severity: "error", message: explanation ?? "The server could not carry that out." };
  }
}

/**
 * The sentence to show for a failed request, whatever was thrown.
 *
 * Lives here rather than inline at the call site so that both arms can be exercised directly: the
 * fetch layer only ever rejects with an `Error`, so a component-level test cannot reach the other
 * one without faking a rejection the application cannot actually produce.
 */
export function failureMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}
