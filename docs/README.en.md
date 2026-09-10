# BareZen-Drive documentation index

This directory holds the architecture, API, development and roadmap documents
for BareZen-Drive. Current version: v0.0.1.

[English](README.en.md) | [简体中文](README.md)

## Documents

- [usage.md](usage.md) (Chinese) / [usage.en.md](usage.en.md) (English):
  **installation and usage** - how to obtain each component, deploy and use the
  server and the Android/Web clients, how the update check works, and the
  current status of desktop and iOS.
- [architecture.md](architecture.md): overall architecture, module layout, server
  and client structure, data model, upload protocol, client internationalization
  (i18n), update check and deployment.
- [api.md](api.md): REST API reference covering authentication, folders, files,
  uploads, thumbnails, signed links, the album, public shares and the version
  endpoint, including the full error-code list.
- [development.md](development.md): local development guide with JDK and Android
  SDK requirements, server and client run commands, test commands, continuous
  integration notes and deployment steps.
- [roadmap.md](roadmap.md): completed scope, in-progress items, later plans and
  known technical debt.

## Suggested reading order

1. Start with [README.md](../README.md) in the repository root for the product
   positioning, feature list, module table and quick start.
2. Read [architecture.md](architecture.md) next to build a mental model of the
   architecture and data model.
3. Read [api.md](api.md) when integrating against the HTTP API.
4. Read [development.md](development.md) to set up an environment or deploy.
5. Read [roadmap.md](roadmap.md) for version scope and future plans.
