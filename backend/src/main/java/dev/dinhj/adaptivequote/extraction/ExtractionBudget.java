package dev.dinhj.adaptivequote.extraction;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Repository
public class ExtractionBudget {
    static final int MONTHLY_LIMIT = 4_000_000;
    static final int RESERVATION = 280_000;

    private final JdbcClient jdbc;

    public ExtractionBudget(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean reserve(LocalDate month, OffsetDateTime startedAt) {
        return jdbc.sql("""
                        INSERT INTO ai_budget (month_start, accounted_micros, last_started_at)
                        VALUES (:month, :amount, :startedAt)
                        ON CONFLICT (month_start) DO UPDATE
                          SET accounted_micros = ai_budget.accounted_micros + :amount,
                              last_started_at = :startedAt
                          WHERE ai_budget.accounted_micros <= :remaining
                            AND ai_budget.last_started_at <= :startedAt - interval '10 seconds'
                        RETURNING accounted_micros
                        """)
                .param("month", month).param("amount", RESERVATION)
                .param("remaining", MONTHLY_LIMIT - RESERVATION).param("startedAt", startedAt)
                .query(Integer.class).optional().isPresent();
    }

    void settle(LocalDate month, int chargedMicros) {
        jdbc.sql("""
                        UPDATE ai_budget SET accounted_micros = accounted_micros - :unused
                         WHERE month_start = :month
                        """)
                .param("month", month).param("unused", RESERVATION - chargedMicros).update();
    }
}
