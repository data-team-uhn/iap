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

import type { Notice } from "@iap/frontend-commons/components/NoticeSnackbar";

import type { ActionResponse } from "./archiveApi";

/**
 * The notice to raise for a restore or purge answer. The title says what happened to the entry, and
 * the message under it is the server's `status.message`, which names the actual reason.
 *
 * `onRetry` is offered only after a failure. A conflict or a veto would only be refused again.
 */
export function describeOutcome(response: ActionResponse, onRetry?: () => void): Notice {
  const explanation = response["status.message"];
  switch (response.status) {
    case "restored": {
      const count = response.restored?.length ?? 0;
      return {
        severity: "success",
        title: count === 1
          ? "Restored 1 item to where it was deleted from."
          : `Restored ${String(count)} items to where they were deleted from.`,
      };
    }
    case "deleted":
      return { severity: "success", title: "The entry and everything archived in it were permanently removed." };
    case "conflict": {
      const detail = (response.conflicts ?? [])
        .map(conflict => `${conflict.originalPath} (${conflict.reason})`)
        .join(", ");
      return {
        severity: "warning",
        title: "Nothing was restored, because something is in the way",
        // Each item and its conflict, or the server's message if it listed none
        message: detail.length > 0 ? detail : explanation,
      };
    }
    case "vetoed":
      return { severity: "warning", title: "A guard refused to destroy this entry", message: explanation };
    case "invalid":
      return { severity: "error", title: "That entry cannot be acted on", message: explanation };
    case "failed":
      return { severity: "error", title: "The server could not carry that out", message: explanation, onRetry };
  }
}

/**
 * The sentence to show for a failed request: the error's message, or the fallback if what was thrown
 * is not an `Error`.
 */
export function failureMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}
