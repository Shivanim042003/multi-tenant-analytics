# Multi-Tenant Real-Time Analytics Platform

A multi-tenant real-time analytics platform built with Java, Spring Boot,
Apache Kafka, Redis, ClickHouse, Docker, and Testcontainers.

## Tech Stack

- Java 21
- Spring Boot
- Maven
- Apache Kafka
- Redis
- ClickHouse
- Docker
- Testcontainers

## Architecture

The platform will ingest events through a Spring Boot API,
stream them through Kafka, process them into ClickHouse,
and provide tenant-scoped analytical queries with Redis caching.

## Current Status

Phase 1 - Project Setup