# Outbound HTTP requests

**Module:** `modules/http-requests` · **Bundle:** `iap-http-requests` ·
**API:** `io.uhndata.iap.httprequests` (`HttpRequests`, `HttpResponse`)

The one piece any module talking to a remote service should use, rather than opening a
connection itself. Built on the JDK's own HTTP client, so the module has no dependencies
at all.

```java
@Reference
private HttpRequests httpRequests;

final HttpResponse response = this.httpRequests.post(url, body, "application/json");
if (!response.isSuccessful()) {
    // reached, and refused
}
```

```java
@NotNull HttpResponse post(String url, String body, String contentType) throws IOException;
@NotNull HttpResponse post(String url, String body, String contentType, Charset charset) throws IOException;
@NotNull HttpResponse post(String url, Map<String, String> headers, Multipart body) throws IOException;
```

The `Multipart` form sends files alongside fields as `multipart/form-data`. Each part is
opened only as it is sent, so a large file is streamed rather than held in memory:

```java
final Multipart body = new Multipart()
    .add("manifest", json, "application/json")
    .add("b0", "scan.pdf", "application/pdf", binary::getStream);
this.httpRequests.post(url, Map.of("Authorization", "Bearer " + token), body);
```

No header is ever quoted in an exception, since headers often carry credentials.

`HttpResponse` carries `getStatusCode()`, `getBody()` and `isSuccessful()`.

Two things to code against:

- **Being refused is not a failure.** Only a request that could not be made at all
  throws `IOException`; a service answering `400` comes back as an unsuccessful
  response. Callers that care must check `isSuccessful()`, because nothing will throw to
  remind them.
- **Every request has a timeout** — 10 seconds to connect, 30 to answer — so a service
  that accepts a connection and then goes quiet cannot hold a scheduled job open
  indefinitely.

Current callers: the chat webhook channel in [notifications](notifications.md), and
[submission transfer](submission-transfer.md).
