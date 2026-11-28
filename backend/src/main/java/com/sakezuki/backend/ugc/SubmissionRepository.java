package com.sakezuki.backend.ugc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class SubmissionRepository {
    private final JdbcClient sql;

    SubmissionRepository(JdbcClient sql) {
        this.sql = sql;
    }

    record Submission(String id, String status, long version, int revision, LocalDateTime createdAt,
                      LocalDateTime updatedAt, String hash) {}
    record Revision(int number, Integer parent, int schemaVersion, String kind, String payload,
                    String attachment, LocalDateTime createdAt) {}
    record Event(int revision, String actorRole, String previous, String next, String reason, LocalDateTime createdAt) {}
    record Option(long no, Long breweryNo, String ja, String ko, String kana) {}

    void lockActiveMember(long member) {
        if (!"ACTIVE".equals(sql.sql("SELECT status FROM MEMBER WHERE no=? FOR UPDATE").param(member)
                .query(String.class).optional().orElse(null))) {
            throw SubmissionErrors.error(401, "사용할 수 없는 계정입니다.");
        }
    }

    Optional<Submission> existing(long owner, UUID key) {
        return sql.sql("SELECT * FROM CATALOG_SUBMISSION WHERE owner_no=? AND creation_key=?")
                .params(owner, key.toString()).query(SubmissionRepository::submission).optional();
    }

    Optional<Submission> find(long owner, UUID id, boolean lock) {
        String query = "SELECT * FROM CATALOG_SUBMISSION WHERE owner_no=? AND id=?";
        return sql.sql(lock ? query + " FOR UPDATE" : query).params(owner, id.toString())
                .query(SubmissionRepository::submission).optional();
    }

    List<Submission> list(long owner, int offset, int limit) {
        return sql.sql("""
                SELECT * FROM CATALOG_SUBMISSION WHERE owner_no=?
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """).params(owner, limit, offset).query(SubmissionRepository::submission).list();
    }

    List<Revision> revisions(long owner, String id) {
        return sql.sql("""
                SELECT r.* FROM SUBMISSION_REVISION r JOIN CATALOG_SUBMISSION s ON s.id=r.submission_id
                WHERE s.owner_no=? AND s.id=? ORDER BY r.revision_no
                """).params(owner, id).query((rs, row) -> new Revision(rs.getInt("revision_no"),
                rs.getObject("parent_revision_no", Integer.class), rs.getInt("schema_version"), rs.getString("revision_kind"),
                rs.getString("payload"), rs.getString("attachment_id"), rs.getObject("created_at", LocalDateTime.class))).list();
    }

    List<Event> events(long owner, String id) {
        return sql.sql("""
                SELECT e.* FROM SUBMISSION_EVENT e JOIN CATALOG_SUBMISSION s ON s.id=e.submission_id
                WHERE s.owner_no=? AND s.id=? ORDER BY e.id
                """).params(owner, id).query((rs, row) -> new Event(rs.getInt("revision_no"), rs.getString("actor_role"),
                rs.getString("previous_status"), rs.getString("next_status"), rs.getString("reason"),
                rs.getObject("created_at", LocalDateTime.class))).list();
    }

    long recentSubmissions(long owner) {
        return sql.sql("""
                SELECT COUNT(*) FROM CATALOG_SUBMISSION WHERE owner_no=?
                  AND created_at > UTC_TIMESTAMP(6) - INTERVAL 1 DAY
                """).param(owner).query(Long.class).single();
    }

    long recentRevisions(long owner) {
        return sql.sql("""
                SELECT COUNT(*) FROM SUBMISSION_REVISION r JOIN CATALOG_SUBMISSION s ON s.id=r.submission_id
                WHERE s.owner_no=? AND r.revision_kind='MEMBER_REVISION'
                  AND r.created_at > UTC_TIMESTAMP(6) - INTERVAL 1 DAY
                """).param(owner).query(Long.class).single();
    }

    long openSubmissions(long owner) {
        return sql.sql("""
                SELECT COUNT(*) FROM CATALOG_SUBMISSION WHERE owner_no=? AND status IN ('PENDING','NEEDS_REVISION')
                """).param(owner).query(Long.class).single();
    }

    void insert(long owner, String id, UUID key, String hash) {
        sql.sql("""
                INSERT INTO CATALOG_SUBMISSION(id,owner_no,status,creation_key,payload_hash)
                VALUES (?,?,'PENDING',?,?)
                """).params(id, owner, key.toString(), hash).update();
    }

    void revision(long owner, String id, int number, Integer parent, String kind, String json, UUID attachment) {
        sql.sql("""
                INSERT INTO SUBMISSION_REVISION(submission_id,revision_no,parent_revision_no,author_no,
                    author_role,revision_kind,payload,attachment_id) VALUES (?,?,?,?,'MEMBER',?,?,?)
                """).params(id, number, parent, owner, kind, json, attachment == null ? null : attachment.toString()).update();
    }

    void initialize(String id, long owner) {
        sql.sql("UPDATE CATALOG_SUBMISSION SET current_revision_no=1 WHERE id=? AND owner_no=?")
                .params(id, owner).update();
    }

    void change(Submission source, long owner, int revision, String status) {
        if (sql.sql("""
                UPDATE CATALOG_SUBMISSION SET current_revision_no=?, status=?, version=version+1,
                  updated_at=UTC_TIMESTAMP(6) WHERE id=? AND owner_no=? AND version=?
                """).params(revision, status, source.id(), owner, source.version()).update() != 1) {
            throw SubmissionErrors.conflict();
        }
    }

    void event(String id, int revision, long owner, String from, String to, String reason) {
        sql.sql("""
                INSERT INTO SUBMISSION_EVENT(submission_id,revision_no,actor_no,actor_role,
                   previous_status,next_status,reason) VALUES (?,?,?,'MEMBER',?,?,?)
                """).params(id, revision, owner, from, to, reason).update();
    }

    boolean breweryExists(long no) {
        return sql.sql("SELECT no FROM BREWERY WHERE no=?").param(no).query(Long.class).optional().isPresent();
    }

    boolean brandBelongs(long brewery, long brand) {
        return sql.sql("SELECT no FROM BRAND WHERE brewery_no=? AND no=?")
                .params(brewery, brand).query(Long.class).optional().isPresent();
    }

    List<Option> options(Long brewery, boolean brands, String term, int offset, int limit) {
        String query = "SELECT no, " + (brands ? "brewery_no," : "NULL AS brewery_no,")
                + "name_ja,name_ko,name_kana FROM " + (brands ? "BRAND WHERE brewery_no=? AND " : "BREWERY WHERE ")
                + "(name_ja LIKE ? ESCAPE '!' OR name_ko LIKE ? ESCAPE '!' OR name_kana LIKE ? ESCAPE '!')"
                + " ORDER BY name_ja,no LIMIT ? OFFSET ?";
        String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var statement = sql.sql(query);
        if (brands) statement.param(brewery);
        return statement.params(pattern, pattern, pattern, limit, offset)
                .query((rs, row) -> new Option(rs.getLong(1), rs.getObject(2, Long.class),
                        rs.getString(3), rs.getString(4), rs.getString(5))).list();
    }

    private static Submission submission(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Submission(rs.getString("id"), rs.getString("status"), rs.getLong("version"),
                rs.getInt("current_revision_no"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class), rs.getString("payload_hash"));
    }
}
