---
title: Caching
description: Redis-backed Spring Cache with typed JSON values.
---

User lookups by id are cached in Redis (`config/CacheConfig.java`). Entries live for `CACHE_TTL` (10 minutes by
default). Keys look like `spring-boot-template:users::42`, so several applications can share one Redis.

## How it behaves

- **Typed JSON.** Values are stored as JSON of one fixed type per cache, never with Java serialization or polymorphic
  type information, so cached data cannot be used for deserialization attacks.
- **Eviction after commit.** The cache manager is transaction-aware, so a rolled-back update never leaves stale data
  behind.
- **Fallback.** If Redis is unreachable, the error is logged and the request goes to the database. Redis is left out of
  `/actuator/health`, so a cache outage doesn't mark the app `DOWN`.

## Caching something new

1. Add a name to `CacheConfig.CacheNames` and register its value type in `redisCacheCustomizer`.
2. Put `@Cacheable(cacheNames = ..., key = "#id")` on the read method.
3. Put `@CacheEvict` on **every** method that changes the data.
4. Cache DTOs, not JPA entities.

```java
@Cacheable(cacheNames = CacheNames.USERS, key = "#id")
public UserResponse getById(long id) { ... }

@Transactional
@CacheEvict(cacheNames = CacheNames.USERS, key = "#id")
public UserResponse update(long id, long requesterId, AdminUpdateUserRequest request) { ... }
```

Inspect the cache with `scripts/db.sh redis-cli`, for example `KEYS spring-boot-template:*` and `TTL <key>`.
