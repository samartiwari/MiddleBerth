package com.middleberth.gateway.repository;

import com.middleberth.gateway.domain.AppUser;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface UserRepository extends ReactiveCrudRepository<AppUser, Long> {

    Mono<AppUser> findByEmail(String email);
}
