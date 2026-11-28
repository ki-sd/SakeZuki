package com.sakezuki.backend.ugc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class AdminReviewAccess {
    private final JdbcClient sql;

    AdminReviewAccess(JdbcClient sql) {
        this.sql = sql;
    }

    void requireCurrent(long actor) {
        var account = sql.sql("""
                SELECT m.status, r.authority FROM MEMBER m
                LEFT JOIN MEMBER_AUTHORITY r ON r.member_no=m.no AND r.authority='ROLE_ADMIN'
                WHERE m.no=?
                """).param(actor).query((rs, row) -> new Account(rs.getString(1), rs.getString(2))).optional();
        var current = account.orElseThrow(AdminReviewAccess::inactive);
        if (!"ACTIVE".equals(current.status())) throw inactive();
        requireRole(current.role());
    }

    // Call inside the mutation transaction. A concurrent DELETE/UPDATE of this role takes an exclusive row lock.
    void lockCurrent(long actor) {
        var status = sql.sql("SELECT status FROM MEMBER WHERE no=? FOR UPDATE").param(actor)
                .query(String.class).optional();
        if (!"ACTIVE".equals(status.orElse(null))) throw inactive();
        var role = sql.sql("""
                SELECT authority FROM MEMBER_AUTHORITY WHERE member_no=? AND authority='ROLE_ADMIN' FOR SHARE
                """).param(actor).query(String.class).optional();
        requireRole(role.orElse(null));
    }

    private static void requireRole(String role) {
        if (!"ROLE_ADMIN".equals(role)) throw SubmissionErrors.error(403, "관리자 권한이 필요합니다.");
    }

    private static org.springframework.web.server.ResponseStatusException inactive() {
        return SubmissionErrors.error(401, "사용할 수 없는 계정입니다.");
    }

    private record Account(String status, String role) {}
}
