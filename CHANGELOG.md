# Changelog

What each release changed for you, newest first. Each line is a commit's summary, linked to its full description and diff.

## 1.0.0 - 2026-11-14

### Features

- Attach the visitor's InternetData record to every request, read with `InternetDataFilter.lookup(request)`: the location, the network and the company behind the address, and the anonymity and hosting flags your plan serves ([`46bd30d`](https://github.com/internetdata/sdk-java-spring-boot/commit/46bd30dff93c4f2ccd0d24facacc9dfda466f7cb))
- Refuse a request with a `block-condition` in `application.yml`, written in the shape of the answer, by country, network type, anonymity or any other member, with 403 or a filter bean of your own ([`46bd30d`](https://github.com/internetdata/sdk-java-spring-boot/commit/46bd30dff93c4f2ccd0d24facacc9dfda466f7cb))
- Choose where the client address comes from: `getRemoteAddr` by default, which Spring Boot's `forward-headers-strategy: native` rewrites behind a proxy you trust, `client-ip-header` for a header your edge writes, or a selector bean of your own ([`46bd30d`](https://github.com/internetdata/sdk-java-spring-boot/commit/46bd30dff93c4f2ccd0d24facacc9dfda466f7cb))
- Fail open on any lookup failure unless `fail-closed` is set, with a 2500 ms bound, no retry and a cache by default ([`46bd30d`](https://github.com/internetdata/sdk-java-spring-boot/commit/46bd30dff93c4f2ccd0d24facacc9dfda466f7cb))
