package com.middleberth.gateway.auth;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface UserRepository extends ReactiveCrudRepository<AppUser, Long> {

    Mono<AppUser> findByEmail(String email);
}
