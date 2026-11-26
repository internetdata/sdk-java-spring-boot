# Changelog

What each release changed for you, newest first. Each line is a commit's summary, linked to its full description and diff.

## 2.0.0 - 2026-11-26

### Breaking changes

- Require internetdata 3.0.0: a keyless answer reads again ([`13c9a49`](https://github.com/internetdata/sdk-java-spring-boot/commit/13c9a49376a24f9e73c912388e60c618e52a57e7))

## 1.0.1 - 2026-11-17

### Features

- Require internetdata 2.10.0: cap_reached, an AS's rank and neighbors, and the plan's name ([`9ffd578`](https://github.com/internetdata/sdk-java-spring-boot/commit/9ffd5780aa75e74fd9c75bcdb2c8d9bec42bed86))

## 1.0.0 - 2026-11-14

### Features

- Attach the visitor's InternetData record to every request, read with `InternetDataFilter.lookup(request)`: the location, the network and the company behind the address, and the anonymity and hosting flags your plan serves ([`46bd30d`](https://github.com/internetdata/sdk-java-spring-boot/commit/46bd30dff93c4f2ccd0d24facacc9dfda466f7cb))
- Refuse a request with a `block-condition` in `application.yml`, written in the shape of the answer, by country, network type, anonymity or any other member, with 403 or a filter bean of your own ([`46bd30d`](https://github.com/internetdata/sdk-java-spring-boot/commit/46bd30dff93c4f2ccd0d24facacc9dfda466f7cb))
- Choose where the client address comes from: `getRemoteAddr` by default, which Spring Boot's `forward-headers-strategy: native` rewrites behind a proxy you trust, `client-ip-header` for a header your edge writes, or a selector bean of your own ([`46bd30d`](https://github.com/internetdata/sdk-java-spring-boot/commit/46bd30dff93c4f2ccd0d24facacc9dfda466f7cb))
- Fail open on any lookup failure unless `fail-closed` is set, with a 2500 ms bound, no retry and a cache by default ([`46bd30d`](https://github.com/internetdata/sdk-java-spring-boot/commit/46bd30dff93c4f2ccd0d24facacc9dfda466f7cb))
