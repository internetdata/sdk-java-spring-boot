# [<img src="https://s3.internetdata.io/internetdata-public/brand/mark.svg" alt="InternetData" height="28"/>](https://internetdata.io/) InternetData Spring Boot Starter

[![Maven Central](https://img.shields.io/maven-central/v/io.internetdata/internetdata-spring-boot-starter.svg)](https://central.sonatype.com/artifact/io.internetdata/internetdata-spring-boot-starter)
[![license](https://img.shields.io/github/license/internetdata/sdk-java-spring-boot.svg)](LICENSE)

The official Spring Boot starter for the [InternetData](https://internetdata.io) API.

It looks up the visitor behind each request, where they are, which network they come from and whether it's a VPN, a proxy, Tor or a hosting server, and hands the answer to your controllers. Blocking is opt-in.

## Getting Started

```xml
<dependency>
    <groupId>io.internetdata</groupId>
    <artifactId>internetdata-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
```

Requires Java 17 or newer and Spring Boot 3 or 4.

Without an API key a lookup is on Unlimited, the free plan: the country, the network and the company behind the address, Tor, relay and anycast, with no daily or monthly cap. Every lookup your app makes comes from your server's one address, though, so a busy site will see bursts refused. A paid plan answers every field, VPN, the proxy flags, hosting, CDN, the bot, carrier, satellite and service flags and the city among them; create a key in the [console](https://app.internetdata.io). A free account tries every field.

```yaml
# application.yml
internetdata:
  api-key: ${INTERNETDATA_API_KEY}
```

If `INTERNETDATA_API_KEY` is unset, Spring leaves `${INTERNETDATA_API_KEY}` as written. The starter then sends no key, so lookups are on Unlimited, and a warning at startup names the variable. To run without a key on purpose, write `${INTERNETDATA_API_KEY:}`.

The filter auto-configures itself. Read the answer anywhere downstream:

```java
@GetMapping("/")
String index(HttpServletRequest request) {
    return InternetDataFilter.lookup(request)
            .flatMap(Lookup::result)
            .map(r -> "Hello, visitor from " + r.location().getCountryName())
            .orElse("Hello");
}
```

By default nothing is blocked. Every request gets an answer and your own code decides what it means: a currency to show, a sign-up to review, a page to keep from some countries.

## Blocking

Set a `block-condition` and a matching request is answered with `403` and never reaches your controllers.

```yaml
internetdata:
  api-key: ${INTERNETDATA_API_KEY}
  block-condition:
    - location: { country: [CU, IR, KP] }
```

A condition is written in the shape of a served answer, keyed by the same names the API uses, and only the members you name are considered, so it can refuse on anything in the answer. The country works on every plan, and so does anycast; a member your plan leaves out is reported as missing, and blocks nobody:

```yaml
block-condition:
  - asn: { type: hosting }            # the network's type, on a paid plan or the trial
  - is_anycast: true                  # anycast
  - is_satellite: true                # satellite space, on a paid plan or the trial
  - is_hosting: true                  # traffic from servers, not people
  - is_cdn: true
  - is_vpn: true                      # anonymity
  - is_tor: true
  - is_vpn: true
    vpn: { provider: nordvpn }        # one provider
  - is_resproxy: true
    resproxy: { hits: { gte: 5 } }    # a numeric threshold
```

The list is OR: any one entry blocking is enough. Values are matched by equality, strings without regard to case. A list *value* means any-of. A map of `gte`/`gt`/`lte`/`lt` compares numbers, and every bound you give must hold, so two of them are a range. Members set to `false` or `null` are ignored, so a condition states the signals you act on; one that constrains nothing would match every request, and is refused when the context starts rather than silently blocking all your traffic.

In Java, build conditions with `Bound.gte(5).andLt(100)` and define your own `Core` bean, so a typo in a bound is a compile error rather than a silently ignored key.

Replace the refusal by defining your own `InternetDataFilter` bean; the auto-configuration backs off to it.

## Where the client address comes from

This is the setting that decides whether any of the above works, and it is the one thing only you can get right.

By default the filter uses `HttpServletRequest#getRemoteAddr`, the socket peer. **The servlet API has no trusted-proxy setting of its own.** Behind nginx, a load balancer, or a CDN, every visitor arrives wearing your proxy's address, and that is the address that gets looked up: every visitor gets your proxy's country and network, and if it is a hosting address, a hosting rule blocks everyone. nginx on the same machine is `127.0.0.1` and a load balancer usually has a private address, which the filter answers locally without a lookup, as an address with no country, and logs a warning.

Spring Boot's own answer is the one to reach for first, because it rewrites `getRemoteAddr` before this filter ever runs:

```yaml
server:
  forward-headers-strategy: native
```

On Tomcat, Spring Boot's default server, `native` hands the header to Tomcat's `RemoteIpValve`. It believes `X-Forwarded-For` only when the connection comes from one of `server.tomcat.remoteip.internal-proxies` (every private and loopback address unless you narrow it), and reads the header from the right, so a visitor cannot choose their own address. Do not use `framework` for this: Spring's `ForwardedHeaderFilter` takes the left-most `X-Forwarded-For` entry from whoever connects, and the visitor writes that one, because nginx, AWS's Application Load Balancer and Cloudflare append to the header rather than replace it.

For an edge that writes the address into its own header, name it:

```yaml
internetdata:
  client-ip-header: CF-Connecting-IP
```

A callable cannot live in YAML, so anything more exotic, a chain depth or your own logic, means defining a `Function<HttpServletRequest, String>` bean named `internetdataIpSelector`. Every bean here is `@ConditionalOnMissingBean`, so yours replaces ours rather than colliding with it.

If the address resolves to a private one, the filter says so once. That is expected locally and is the signal to fix your configuration anywhere else.

## When a lookup fails

The request is let through, and the reason is on `lookup.error()`. Our outage should not become yours, so a network failure, a rate limit or a rejected key all fail open. Set `fail-closed: true` to block instead. Private addresses are answered locally and never fail, so this will not lock you out in development.

## Cost and latency

Answers are cached for an hour, so a returning visitor costs nothing, and private addresses never leave the process. A cache miss is one request to our API, bounded at 2500 ms by default and not retried: on a request path, failing open quickly beats holding a visitor while we try again.

Skip what you do not care about:

```yaml
internetdata:
  skip-paths: ["/actuator/**", "/static/**"]
```

Beyond a few million distinct visitors a day, stop calling the API per request: look addresses up locally in [our databases](https://internetdata.io/databases) instead.

## Absent is not false

Unlimited, with a key or without one, leaves out `isVpn()`, `isHosting()`, `isCdn()`, the four proxy flags and the bot, carrier, satellite and service flags, the detail behind them, and the paid members inside `location()`, `asn()`, `company()` and `abuse()`, such as `location().getCity()`. A member your plan does not include is an empty `Optional`, or null inside those four objects, which means "not in your plan" rather than "checked, and no". The bot, carrier, satellite and service flags are plain booleans, so one your plan leaves out reads `false`, and a `block-condition` on one reports it as missing.

```java
result.isVpn().orElse(false)   // when you only want the flag
```

A `block-condition` naming a member your plan does not serve, `location.city` on Unlimited included, can never match, so the filter warns once instead of failing silently. Set `on-missing-field: throw` to make it an error.

## Other Libraries

There are official InternetData client libraries available for many languages including PHP, Python, Go, Java, and Ruby. See our GitHub at https://github.com/internetdata for more.

## About InternetData

Geolocation, anonymity, ownership, network, service and domain data for any IP address, AS or domain, by API or as databases you download.

[<picture><source media="(prefers-color-scheme: dark)" srcset="https://s3.internetdata.io/internetdata-public/brand/logo-dark.svg"><img src="https://s3.internetdata.io/internetdata-public/brand/logo.svg" alt="InternetData" height="40"/></picture>](https://internetdata.io/)

## License

This project is licensed under the [MIT License](LICENSE).
