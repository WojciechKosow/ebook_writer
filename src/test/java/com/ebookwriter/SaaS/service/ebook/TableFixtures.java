package com.ebookwriter.SaaS.service.ebook;

/**
 * Tables in the shapes technical books produce — an endpoint map, an error
 * contract, a symptom / cause / fix / note table full of class names and
 * exception messages, configuration keys, a seven-column schema table — for the
 * width tests.
 */
final class TableFixtures {

    private TableFixtures() {
    }

    static String tables() {
        return """
                The controller exposes these endpoints:

                | Method | Path | Auth | Request body | Success response |
                |---|---|---|---|---|
                | `POST` | `/api/auth/register` | public | `RegisterRequest` | `201 Created` with `UserResponse` |
                | `POST` | `/api/auth/login` | public | `LoginRequest` | `200 OK` with a JWT in `AuthResponse` |
                | `GET` | `/api/offers/{offerId}/reviews?page=0&size=20` | `ROLE_USER` | none | `200 OK` with `Page<ReviewResponse>` |
                | `PUT` | `/api/offers/{offerId}` | `ROLE_SELLER` (owner only) | `UpdateOfferRequest` | `200 OK` with `OfferResponse` |
                | `DELETE` | `/api/admin/users/{userId}/roles/{roleName}` | `ROLE_ADMIN` | none | `204 No Content` |

                The resulting error contract:

                | Exception | HTTP status | Error code | Message shown to the client |
                |---|---|---|---|
                | `OfferNotFoundException` | `404 Not Found` | `OFFER_NOT_FOUND` | The offer does not exist or was soft-deleted. |
                | `MethodArgumentNotValidException` | `400 Bad Request` | `VALIDATION_FAILED` | A list of field errors, one per invalid field, with the rejected value. |
                | `DataIntegrityViolationException` | `409 Conflict` | `EMAIL_ALREADY_REGISTERED` | An account with this email already exists. |
                | `AccessDeniedException` | `403 Forbidden` | `ACCESS_DENIED` | You are not allowed to modify this resource. |

                What went wrong, and why:

                | Symptom | Real cause | Fix | Note |
                |---|---|---|---|
                | `LazyInitializationException` when serialising `Order.items` | The session closed before Jackson touched the lazy collection outside the transaction | Fetch the items with `@EntityGraph(attributePaths = "items")` in `OrderRepository.findWithItemsById` | Do not switch on `spring.jpa.open-in-view`; it hides the problem |
                | Every request returns `401 Unauthorized` after login | `JwtAuthenticationFilter` read the header `authorization` but the client sent `Authorization: Bearer <token>` | Read `HttpHeaders.AUTHORIZATION` and strip the `Bearer ` prefix | Header names are case-insensitive in HTTP, not in `Map.get` |
                | `org.springframework.dao.DataIntegrityViolationException: could not execute statement; SQL [n/a]; constraint [uk_user_email]` | Duplicate email insert | Check `existsByEmail` first and map the exception in `GlobalExceptionHandler` | — |

                A small table:

                | Key | Value |
                |---|---|
                | port | 8080 |
                | profile | dev |

                Configuration keys:

                | Property | Default | Meaning |
                |---|---|---|
                | `spring.datasource.hikari.maximum-pool-size` | 10 | Upper bound on pooled JDBC connections shared by all request threads. |
                | `app.security.jwt.access-token-expiration-ms` | 900000 | How long an access token is valid before the client must refresh it. |
                | `management.endpoints.web.exposure.include` | `health,info` | Which actuator endpoints are reachable over HTTP. |

                Seven columns:

                | Entity | Table | PK | Unique | FK | Soft delete | Audited |
                |---|---|---|---|---|---|---|
                | `User` | `users` | `id` | `email` | — | no | `createdAt`, `updatedAt` |
                | `Offer` | `offers` | `id` | — | `seller_id` → `users.id` | `deleted_at` | yes |
                | `OrderItem` | `order_items` | `id` | (`order_id`, `offer_id`) | `order_id`, `offer_id` | no | no |
                """;
    }
}
