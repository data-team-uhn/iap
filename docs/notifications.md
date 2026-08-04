# Notifications

IAP tells people about itself through **notification** modules: something gathers what is worth
saying, and a channel delivers it. The first channel is a chat webhook.

## Sending HTTP requests

`iap-http-requests` (`modules/http-requests`) is the small piece every outbound channel needs: an
`HttpRequests` OSGi service that posts a body to a URL and hands back what the service answered.

```java
@Reference
private HttpRequests httpRequests;
...
final HttpResponse response = this.httpRequests.post(url, body, "application/json");
if (!response.isSuccessful()) {
    // The service was reached and refused the request
}
```

Two things worth knowing:

- **Being refused is not a failure.** Only a request that could not be made at all throws; a service
  answering `400` comes back as an unsuccessful `HttpResponse`. Callers that care must check
  `isSuccessful()`, because nothing will throw to remind them.
- **Every request has a timeout** — 10 seconds to connect, 30 to answer — so a service that accepts
  a connection and then goes quiet cannot hold up a scheduled job forever.

It is built on the HTTP client the Java platform provides, so the module needs no dependencies at
all.

## Scheduled chat notifications

`iap-slack-notifications` (`modules/slack-notifications`) posts a message to a chat webhook on a
schedule. Each configuration is one scheduled job, so an instance can have a nightly summary and a
weekly digest going to different channels.

### Configuring one

A factory configuration for `Scheduled Slack Notifications`:

| Setting | Meaning |
| --- | --- |
| `name` | Names this notification, and its scheduled job |
| `schedule` | A Quartz expression, e.g. `0 0 0 * * ? *` nightly or `0 0 9 ? * MON *` Monday mornings; defaults to nightly |
| `endpoint` | The webhook address to post to |
| `title` | An optional line above the attachments |
| `include` | Which producers to use, empty for all of them |
| `notificationParameters` | `key=value` strings passed to the producers |
| `skipEmpty` | Post nothing at all when there is nothing to report (the default), rather than a "Nothing to report" message |

A value starting with **`%ENV%`** is read from the named environment variable instead of being used
directly — `%ENV%SLACK_NOTIFICATIONS_ENDPOINT` reads `$SLACK_NOTIFICATIONS_ENDPOINT`. A webhook
address is a secret and belongs in the environment, not in a configuration file.

A notification whose endpoint resolves to nothing is **not scheduled at all**, and says so once in
the log. Scheduling a job that could only ever fail would report the same failure every night
forever.

### Providing messages

Implement the `SlackNotificationProducer` SPI (`io.uhndata.iap.slacknotifications.spi`) as an OSGi
service: a name, and a `prepareMessages(extraParameters)` returning a list of webhook attachment
objects. A producer with nothing to say returns an **empty list**; that counts as silence, so a
notification whose producers are all quiet posts nothing when `skipEmpty` is set.

### The status report producer

`StatusReportNotification` (named `status`) turns the [system status report](status.md) into
attachments, one per report, coloured by status level. It reads three extra parameters:

- `statusReport.targetStatusLevel` — the lowest level to include, `INFO` by default. An
  unrecognized level falls back to `INFO` rather than costing the whole notification;
- `statusReport.includeTags` — a comma-separated list of status tags, all of them by default;
- `statusReport.unprivileged` — `true` to generate the report for an unprivileged audience.

Because it goes through the status SPI, it picks up every registered reporter automatically —
including the [error tracking](error-tracking.md) one, once both are deployed, with no coupling
between those two modules.

## Email

`iap-email-notifications` (`modules/email-notifications`) sends emails built from templates kept in
the repository, so a deployment can reword what the platform says without touching code.

### A template

An `iap:EmailTemplate` node, whose properties carry the addressing and whose child files carry the
body:

| Property | Meaning |
| --- | --- |
| `senderAddress` | Required. The `From` address |
| `senderName` | The display name shown with it |
| `replyToAddress`, `replyToName` | Where replies go, defaulting to the sender |
| `subject` | Required. May itself contain `${variable}` placeholders |
| *anything else* | A variable available to the subject and the body |

| Child | Meaning |
| --- | --- |
| `bodyTemplate.html` | The HTML body. Optional |
| `bodyTemplate.txt` | The plain text body. Optional, but a template needs at least one of the two |
| `bodyTemplate.header.html`, `bodyTemplate.footer.html` | Wrapped around the HTML body |
| `bodyTemplate.header.txt`, `bodyTemplate.footer.txt` | Wrapped around the plain text body |
| *any other file* | Sent as an inline attachment, referenced from the HTML body as `cid:<file name>` |

Headers, footers and attachments shared by every email live under **`/libs/iap/mailTemplates/`**. A
template picks up the shared headers and footers automatically, overrides them by carrying its own,
and asks for a shared attachment by setting an `includeAttachment_<file name>` property.

### Sending one

```java
final EmailTemplate template = EmailTemplate.builder(templateNode, resolver).build();
final Email email = template.getEmailBuilder(Map.of("who", "Alice"))
    .withRecipient("alice@example.invalid", "Alice")
    .build();
EmailUtils.sendHtmlEmail(email, this.mailService);
```

`getEmailBuilder(variables)` fills in the subject and both bodies; what the caller passes overrides
the template's own properties. `sendTextEmail` sends only the plain text part; `sendHtmlEmail` sends
the HTML one, the plain text part as a fallback for the clients that cannot show it, and the inline
attachments. Since either body part may be missing, each method needs the one it sends: asking for
the plain text form of a template that only has an HTML body is an `IllegalArgumentException`, rather
than an email with no body at all.

### Checking that mail works at all

`GET /content.emailtest.html?fromEmail=…&fromName=…&toEmail=…&toName=…` (add `isHtml=true` for a
rich-text one) sends one fixed message, so an administrator can tell whether the SMTP configuration
of an instance works without waiting for the platform to have a reason to write to somebody. It is
**restricted to `admin`**, since it mails an arbitrary address.

SMTP settings come from the module's feature configuration — host, port, credentials — with the
password decrypted through Sling's crypto service, keyed by the `SLING_COMMONS_CRYPTO_PASSWORD`
environment variable.

## Future work

- **Nothing produces messages yet** beyond the status report, and nothing sends an email yet. Both
  are wiring, and the workflow engine is the natural place for it: an email belongs to a submission
  changing state.
- **Filling a template in from a submission.** Variables are a plain map today. Resolving
  `${...}` placeholders from a submission's own answers would make templates far more useful, and
  is best designed together with the workflow actions that will trigger them.
