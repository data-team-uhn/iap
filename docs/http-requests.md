# Outbound HTTP requests

**Module:** `modules/http-requests` · **Bundle:** `iap-http-requests` ·
**API:** `io.uhndata.iap.httprequests.api` (`HttpRequests`, `HttpResponse`)

Sends HTTP POST requests to services outside IAP. It uses the JDK's `HttpClient`, and
needs no other library.

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
```

The body is sent in UTF-8 unless a charset is given. `contentType` is a media type
without parameters, and the request adds the charset to it: `application/json` goes out
as `application/json; charset=UTF-8`.

`HttpResponse` carries `getStatusCode()`, `getBody()` and `isSuccessful()`, which is
true for a `2xx` status.

Three things to code against:

- **Being refused is not a failure.** A service answering `400` comes back as a response
  that is not successful, and nothing throws. Only a request that could not be made, or
  got no answer in time, throws `IOException`. Check `isSuccessful()`.
- **A request waits 10 seconds to connect, and 30 seconds from sending for the whole
  response, body included.** A service that stops answering part way fails the request
  with an `HttpTimeoutException`, which is an `IOException`.
- **An address can be a secret**, such as a chat webhook's token. The module's messages
  name at most the scheme, host and port of the service. A caller that names the service
  in a message of its own should do the same, with `URI.getHost()`.
