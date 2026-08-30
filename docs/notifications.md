# Notifications

IAP tells people about itself through **notification channels**. Something gathers what
is worth saying; a channel delivers it. The two are separate modules on purpose, so a
new channel reaches every existing source of messages and a new source reaches every
channel.

| Channel | Module | Delivers |
|---|---|---|
| Chat webhook | `modules/slack-notifications` | Scheduled posts to a webhook, assembled from producers |
| Email | `modules/email-notifications` | Emails built from templates kept in the repository |

More channels are expected. A channel is an ordinary module: it decides what triggers a
delivery, what a message looks like in its medium, and how the platform's content
becomes one. Where a channel needs to reach a remote service it uses
[`HttpRequests`](http-requests.md) rather than talking to the network itself.

## Chat webhooks

Each factory configuration of `Scheduled Slack Notifications` is one scheduled job, so
an instance can run a nightly summary and a weekly digest to different channels.

| Setting | Default | Meaning |
|---|---|---|
| `name` | — | Names the notification and its job |
| `schedule` | `%ENV%SLACK_NOTIFICATIONS_SCHEDULE` | Quartz expression — `0 0 0 * * ? *` nightly, `0 0 9 ? * MON *` Monday mornings |
| `endpoint` | `%ENV%SLACK_NOTIFICATIONS_ENDPOINT` | The webhook address |
| `title` | empty | An optional line above the attachments |
| `include` | — | Which producers to use; empty means all |
| `notificationParameters` | — | `key=value` strings passed to the producers |
| `skipEmpty` | `true` | Post nothing when there is nothing to report, rather than "Nothing to report" |

A value starting with **`%ENV%`** names an environment variable to read instead of being
used directly — `%ENV%SLACK_NOTIFICATIONS_ENDPOINT` reads `$SLACK_NOTIFICATIONS_ENDPOINT`.
Both the schedule and the endpoint default to that form: a webhook address is a secret
and belongs in the environment, not in a configuration file.

**A notification whose endpoint resolves to nothing is not scheduled at all**, and says
so once in the log. Scheduling a job that could only ever fail would report the same
failure every night forever.

### Providing messages

What a chat notification says comes from producers, not from the channel. Implement
`SlackNotificationProducer` (`…slacknotifications.spi`) as an OSGi component:

```java
@NotNull String           getName();
@NotNull List<JsonObject> prepareMessages(@NotNull Map<String, String> extraParameters);
```

The SPI also carries the attachment keys — `TITLE`, `TEXT`, `COLOR` — and the four
colours to use, so producers agree on what a severity looks like: `INFO` `999`,
`SUCCESS` `393`, `WARNING` `BA0`, `ERROR` `900`.

**Return an empty list to say nothing.** That counts as silence, so a notification whose
producers are all quiet posts nothing at all when `skipEmpty` is set.

### The status report producer

`StatusReportNotification` (named `status`) turns the [status report](status.md) into
attachments, one per report, coloured by level. Three extra parameters:

| Parameter | Default | Means |
|---|---|---|
| `statusReport.targetStatusLevel` | `INFO` | The lowest level to include. An unrecognized level falls back to `INFO` rather than costing the whole notification |
| `statusReport.includeTags` | all | Comma-separated status tags |
| `statusReport.unprivileged` | `false` | Generate the report for an unprivileged audience |

Because it goes through the status SPI it picks up every registered reporter
automatically — including [error tracking](error-tracking.md) — with no coupling between
those modules. A producer for another channel would get the same reports for free.

## Email

Emails are built from `mail:Template` nodes in the repository, so a deployment can
reword what the platform says without touching code.

### A template

| Property | Meaning |
|---|---|
| `senderAddress` | **Required.** The `From` address |
| `senderName` | Display name shown with it |
| `replyToAddress`, `replyToName` | Where replies go, defaulting to the sender |
| `subject` | **Required.** May contain `${variable}` placeholders |
| *anything else* | A variable available to the subject and both bodies |

| Child file | Meaning |
|---|---|
| `bodyTemplate.html` | The HTML body |
| `bodyTemplate.txt` | The plain text body |
| `bodyTemplate.header.html`, `bodyTemplate.footer.html` | Wrapped around the HTML body |
| `bodyTemplate.header.txt`, `bodyTemplate.footer.txt` | Wrapped around the plain text body |
| *any other file* | An inline attachment, referenced from the HTML as `cid:<file name>` |

Both bodies are optional, but a template needs at least one.

Headers, footers and attachments shared by every email live under
**`/libs/iap/mailTemplates/`** (`EmailTemplate.COMMON_TEMPLATES_PATH`). A template picks
up the shared headers and footers automatically, overrides them by carrying its own, and
asks for a shared attachment with an `includeAttachment_<file name>` property
(`INCLUDE_ATTACHMENT_PREFIX`).

**A header and footer never make a body on their own.** A template with no
`bodyTemplate.txt` has no plain text part at all, even where shared plain text headers
and footers exist — and the same for HTML.

### Sending one

```java
final EmailTemplate template = EmailTemplate.builder(templateNode, resolver).build();
final Email email = template.getEmailBuilder(Map.of("who", "Alice"))
    .withRecipient("alice@example.invalid", "Alice")
    .build();
EmailUtils.sendHtmlEmail(email, this.mailService);
```

`getEmailBuilder(variables)` fills in the subject and both bodies; what the caller passes
overrides the template's own properties. `EmailTemplate.builder()` also takes no
arguments, for a template assembled in code rather than read from a node.

`sendTextEmail` sends only the plain text part. `sendHtmlEmail` sends the HTML one, the
plain text part as a fallback for clients that cannot show it, and the inline
attachments. Since either part may be missing, **each method requires the part it sends**:
asking for the text form of an HTML-only template raises `IllegalArgumentException`
rather than sending an email with no body.

### Checking that mail works

```
GET /content.emailtest.html?fromEmail=…&fromName=…&toEmail=…&toName=…[&isHtml=true]
```

Sends one fixed message, so an administrator can tell whether an instance's SMTP
configuration works without waiting for the platform to have a reason to write to
somebody. **Restricted to `admin`**, since it mails an arbitrary address.

Sending is asynchronous, so the endpoint waits for it to finish before answering, for up
to fifteen seconds. It reports `200` once the message has gone out, `500` if it was
refused, and `202` if the send is still going when the wait runs out — a relay that never
answers must not park the request thread indefinitely. The outcome reaches the instance
log either way, which is the only place a send that outlives the wait can report to.

A failure answers that it failed and nothing more: what the mail server said goes to the
log, because that text routinely names the relay, its port and the account the instance
authenticates with.

SMTP settings come from the module's feature configuration —
`emailnotifications.smtps.host`, `.port`, `.username`, `.password`,
`.checkserveridentity`, `.from` — with the password decrypted through Sling's crypto
service, keyed by the `SLING_COMMONS_CRYPTO_PASSWORD` environment variable.

## Future work

- **Nothing produces messages yet** beyond the status report, and nothing sends an email
  yet. Both are wiring, and the workflow engine is the natural place for it: an email
  belongs to a submission changing state.
- **Filling a template in from a submission.** Variables are a plain map today.
  Resolving `${…}` placeholders from a submission's own answers would make templates far
  more useful, and is best designed alongside the workflow actions that will trigger
  them.
- **A shared notion of a message.** Chat producers return webhook attachments, and email
  is written as templates; a third channel would need one or the other, or something
  above both. Worth settling when there is a second source of messages to design it
  against.
