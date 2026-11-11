package com.bankcore.transfer.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyLock {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void acquire(String lockName) {
        jdbcTemplate.queryForObject("""
                select 1
                from (select pg_advisory_xact_lock(hashtextextended(?, 0))) acquired
                """, Integer.class, lockName);
    }
}
