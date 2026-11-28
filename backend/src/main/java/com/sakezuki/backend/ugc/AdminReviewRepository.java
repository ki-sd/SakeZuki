package com.sakezuki.backend.ugc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class AdminReviewRepository {
    enum Status { PENDING, NEEDS_REVISION, CANCELLED, REJECTED, APPROVED }
    enum Sort { CREATED, UPDATED }

    static final int CANDIDATE_LIMIT = 20;
    static final int SOURCE_LIMIT = 200;
    private static final String DUPLICATES_SQL = """
            SELECT s.no,s.name_ja,s.name_ko,s.name_kana,s.sake_type,s.brewery_no,s.brand_no,
                   br.name_ja AS brewery_ja,br.name_ko AS brewery_ko,
                   bd.name_ja AS brand_ja,bd.name_ko AS brand_ko,
                   COALESCE(s.name_ja=:ja,FALSE) AS exact_ja,COALESCE(s.name_ko=:ko,FALSE) AS exact_ko,
                   COALESCE(s.name_kana=:kana,FALSE) AS exact_kana,
                   COALESCE(s.name_ja LIKE :jaPrefix ESCAPE '!',FALSE) AS prefix_ja,
                   COALESCE(s.name_ko LIKE :koPrefix ESCAPE '!',FALSE) AS prefix_ko,
                   COALESCE(s.name_kana LIKE :kanaPrefix ESCAPE '!',FALSE) AS prefix_kana,
                   %1$s AS brewery_match, %2$s AS brand_match,
                   COALESCE(s.sake_type=:designation,FALSE) AS designation_match
            FROM SAKE s
            JOIN BREWERY br ON br.no=s.brewery_no
            LEFT JOIN BRAND bd ON bd.no=s.brand_no
            WHERE s.name_ja=:ja OR s.name_ko=:ko OR s.name_kana=:kana
               OR s.name_ja LIKE :jaPrefix ESCAPE '!' OR s.name_ko LIKE :koPrefix ESCAPE '!'
               OR s.name_kana LIKE :kanaPrefix ESCAPE '!'
            ORDER BY (exact_ja+exact_ko+exact_kana) DESC,
                     %1$s DESC, %2$s DESC, COALESCE(s.sake_type=:designation,FALSE) DESC,
                     s.no
            LIMIT :limit
            """;

    private final JdbcClient sql;

    AdminReviewRepository(JdbcClient sql) {
        this.sql = sql;
    }

    record Head(String id, long owner, String status, long version, int revision,
                String nameJa, String nameKo, LocalDateTime createdAt, LocalDateTime updatedAt, JobSummary approvalJob) {}
    record JobSummary(long id, String state, boolean active, int attempts, String failureCode) {}

    private static final String JOB_FIELDS = """
            j.id AS job_id,j.state AS job_state,j.active_submission IS NOT NULL AS job_active,
            j.attempts AS job_attempts,j.failure_code AS job_failure
            """;
    private static final String JOB_JOIN = """
            LEFT JOIN SUBMISSION_IMAGE_APPROVAL_JOB j ON j.id=(
                SELECT MAX(recent.id) FROM SUBMISSION_IMAGE_APPROVAL_JOB recent
                WHERE recent.submission_id=s.id)
            """;
    record Revision(int number, Integer parent, long author, String role, int schema, String kind,
                    String payload, String attachmentId, String attachmentStatus, String stagingState,
                    String rightsState, boolean previewable, LocalDateTime createdAt) {}
    record Event(int revision, long actor, String role, String from, String to, String reason, LocalDateTime createdAt) {}
    record Decision(String hash, String submission, int revision, String action, long version) {}
    record Names(String ja, String ko) {}
    record Candidate(long no, String ja, String ko, String kana, String designation,
                     long breweryNo, Long brandNo, Names brewery, Names brand, List<String> nameMatches) {}
    record RankedCandidate(Candidate catalog, boolean breweryMatch, boolean brandMatch, boolean designationMatch) {}
    record Source(long sakeNo, String system, String externalKey) {}

    List<Head> list(Status status, Sort sort, String query, int offset, int limit) {
        String order = " ORDER BY s." + (sort == Sort.CREATED ? "created_at" : "updated_at") + " DESC, s.id DESC";
        String page = """
                SELECT s.id,s.owner_no,s.status,s.version,s.current_revision_no,s.created_at,s.updated_at,
                       %1$s AS name_ja, %2$s AS name_ko
                FROM CATALOG_SUBMISSION s
                JOIN SUBMISSION_REVISION r ON r.submission_id=s.id AND r.revision_no=s.current_revision_no
                WHERE 1=1
                """.formatted(jsonName("ja"),jsonName("ko")) + (status == null ? "" : " AND s.status=:status\n")
                + (query == null || query.isEmpty() ? "" : """
                    AND (%s LIKE :search ESCAPE '!' OR %s LIKE :search ESCAPE '!' OR %s LIKE :search ESCAPE '!')
                    """.formatted(jsonName("ja"),jsonName("ko"),jsonName("kana")))
                + order + " LIMIT :limit OFFSET :offset";
        String statement = "SELECT s.*, " + JOB_FIELDS + " FROM (" + page + ") s " + JOB_JOIN + order;
        var params = sql.sql(statement).param("limit", limit).param("offset", offset);
        if (status != null) params.param("status", status.name());
        if (query != null && !query.isEmpty()) params.param("search", "%" + escapeLike(query) + "%");
        return params.query(AdminReviewRepository::head).list();
    }

    private static String jsonName(String field) {
        String value = "JSON_EXTRACT(r.payload,'$.sake." + field + "')";
        return "CASE WHEN JSON_TYPE(" + value + ")='STRING' THEN JSON_UNQUOTE(" + value + ") END";
    }

    Optional<Head> find(UUID id, boolean lock) {
        String fields = lock ? "NULL AS job_id" : JOB_FIELDS;
        String query = """
                SELECT s.id,s.owner_no,s.status,s.version,s.current_revision_no,s.created_at,s.updated_at,
                       NULL AS name_ja,NULL AS name_ko,
                """ + fields + " FROM CATALOG_SUBMISSION s " + (lock ? "" : JOB_JOIN) + " WHERE s.id=?";
        return sql.sql(lock ? query + " FOR UPDATE" : query).param(id.toString())
                .query(AdminReviewRepository::head).optional();
    }

    Revision current(Head head) {
        return sql.sql("""
                SELECT r.*, a.status AS attachment_status, NULL AS storage_state,
                       NULL AS rights_state, FALSE AS previewable
                FROM SUBMISSION_REVISION r
                LEFT JOIN SUBMISSION_ATTACHMENT a ON a.id=r.attachment_id AND a.submission_id=r.submission_id
                    AND a.owner_no=? AND a.asset_id=a.id
                WHERE r.submission_id=? AND r.revision_no=?
                """).params(head.owner(),head.id(),head.revision()).query(AdminReviewRepository::revision).single();
    }

    List<Revision> revisions(Head head) {
        return sql.sql("""
                SELECT r.*, a.status AS attachment_status, o.storage_state,
                       (SELECT e.decision FROM IMAGE_RIGHTS_EVENT e WHERE e.asset_id=a.asset_id
                            ORDER BY e.id DESC LIMIT 1) AS rights_state,
                       (v.asset_id IS NOT NULL AND a.status='READY'
                        AND s.status IN ('PENDING','NEEDS_REVISION') AND m.status='ACTIVE') AS previewable
                FROM SUBMISSION_REVISION r JOIN CATALOG_SUBMISSION s ON s.id=r.submission_id
                JOIN MEMBER m ON m.no=s.owner_no
                LEFT JOIN SUBMISSION_ATTACHMENT a ON a.id=r.attachment_id AND a.submission_id=s.id AND a.owner_no=s.owner_no
                LEFT JOIN IMAGE_OBJECT o ON o.asset_id=a.asset_id AND o.storage_area='STAGING'
                LEFT JOIN UGC_VERIFIED_STAGING v ON v.asset_id=a.asset_id
                WHERE r.submission_id=? ORDER BY r.revision_no
                """).param(head.id()).query(AdminReviewRepository::revision).list();
    }

    List<Event> events(Head head) {
        return sql.sql("""
                SELECT revision_no,actor_no,actor_role,previous_status,next_status,reason,created_at
                FROM SUBMISSION_EVENT WHERE submission_id=? ORDER BY id
                """).param(head.id()).query((rs, row) -> new Event(rs.getInt(1), rs.getLong(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, LocalDateTime.class))).list();
    }

    Optional<Decision> decision(long actor, UUID key) {
        return sql.sql("""
                SELECT request_hash,submission_id,revision_no,decision,result_version
                FROM SUBMISSION_REVIEW_DECISION WHERE actor_no=? AND request_key=?
                """).params(actor, key.toString()).query((rs, row) -> new Decision(rs.getString(1), rs.getString(2),
                    rs.getInt(3), rs.getString(4), rs.getLong(5))).optional();
    }

    void recordDecision(long actor, UUID key, String hash, Head head, String action, String reason) {
        sql.sql("""
                INSERT INTO SUBMISSION_REVIEW_DECISION(actor_no,request_key,request_hash,submission_id,
                    revision_no,decision,result_version,reason) VALUES (?,?,?,?,?,?,?,?)
                """).params(actor,key.toString(),hash,head.id(),head.revision(),action,head.version()+1,reason).update();
    }

    void advance(Head head, int revision, String status) {
        if (sql.sql("""
                UPDATE CATALOG_SUBMISSION SET current_revision_no=?, status=?, version=version+1, updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND version=? AND status='PENDING'
                """).params(revision, status, head.id(), head.version()).update() != 1) throw SubmissionErrors.conflict();
    }

    List<RankedCandidate> candidates(SubmissionInput draft) {
        return sql.sql(duplicatesSql(draft)).params(duplicateParameters(draft)).query((rs, row) -> {
            var matches = new java.util.ArrayList<String>();
            for (String name : List.of("ja", "ko", "kana")) {
                if (rs.getBoolean("exact_" + name)) matches.add("EXACT_" + name.toUpperCase(java.util.Locale.ROOT));
                else if (rs.getBoolean("prefix_" + name)) matches.add("PREFIX_" + name.toUpperCase(java.util.Locale.ROOT));
            }
            var catalog = new Candidate(rs.getLong("no"), rs.getString("name_ja"), rs.getString("name_ko"),
                    rs.getString("name_kana"), rs.getString("sake_type"), rs.getLong("brewery_no"),
                    rs.getObject("brand_no", Long.class), new Names(rs.getString("brewery_ja"),rs.getString("brewery_ko")),
                    new Names(rs.getString("brand_ja"),rs.getString("brand_ko")), matches);
            return new RankedCandidate(catalog, rs.getBoolean("brewery_match"),
                    rs.getBoolean("brand_match"), rs.getBoolean("designation_match"));
        }).list();
    }

    static String duplicatesSql(SubmissionInput draft) {
        String breweryMatch = draft.brewery().proposed() == null
                ? "COALESCE(s.brewery_no=:brewery,FALSE)"
                : "COALESCE(br.name_ja=:breweryJa OR br.name_ko=:breweryKo,FALSE)";
        String brandMatch = draft.brand() == null || draft.brand().proposed() == null
                ? "COALESCE(s.brand_no=:brand,FALSE)"
                : "COALESCE(bd.name_ja=:brandJa OR bd.name_ko=:brandKo,FALSE)";
        return DUPLICATES_SQL.formatted(breweryMatch,brandMatch);
    }

    static java.util.Map<String, Object> duplicateParameters(SubmissionInput draft) {
        var params = new java.util.HashMap<String, Object>();
        params.put("ja", draft.sake().ja());
        params.put("ko", draft.sake().ko());
        params.put("kana", draft.sake().kana());
        params.put("jaPrefix", prefix(draft.sake().ja()));
        params.put("koPrefix", prefix(draft.sake().ko()));
        params.put("kanaPrefix", prefix(draft.sake().kana()));
        choiceParameters(params, "brewery", draft.brewery());
        choiceParameters(params, "brand", draft.brand());
        params.put("designation", draft.designation());
        params.put("limit", CANDIDATE_LIMIT);
        return params;
    }

    private static void choiceParameters(java.util.Map<String, Object> params, String key, SubmissionInput.Choice choice) {
        var proposed = choice == null ? null : choice.proposed();
        params.put(key, choice == null ? null : choice.existingNo());
        params.put(key + "Ja", proposed == null ? null : proposed.ja());
        params.put(key + "Ko", proposed == null ? null : proposed.ko());
    }

    List<Source> sources(List<Long> candidates) {
        if (candidates.isEmpty()) return List.of();
        return sql.sql("""
                SELECT sake_no,source_system,external_key FROM SAKE_SOURCE
                WHERE sake_no IN (:numbers) AND external_key IS NOT NULL
                ORDER BY sake_no,source_system,external_key LIMIT :limit
                """).param("numbers", candidates).param("limit", SOURCE_LIMIT + 1).query((rs, row) ->
                    new Source(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
    }

    private static Head head(ResultSet rs, int row) throws SQLException {
        Long jobId = rs.getObject("job_id",Long.class);
        var job = jobId == null ? null : new JobSummary(jobId,rs.getString("job_state"),rs.getBoolean("job_active"),
                rs.getInt("job_attempts"),rs.getString("job_failure"));
        return new Head(rs.getString("id"),rs.getLong("owner_no"),rs.getString("status"),rs.getLong("version"),
                rs.getInt("current_revision_no"),rs.getString("name_ja"),rs.getString("name_ko"),
                rs.getObject("created_at",LocalDateTime.class),rs.getObject("updated_at",LocalDateTime.class),job);
    }

    private static Revision revision(ResultSet rs, int row) throws SQLException {
        return new Revision(rs.getInt("revision_no"),rs.getObject("parent_revision_no",Integer.class),rs.getLong("author_no"),
                rs.getString("author_role"),rs.getInt("schema_version"),rs.getString("revision_kind"),rs.getString("payload"),
                rs.getString("attachment_id"),rs.getString("attachment_status"),rs.getString("storage_state"),
                rs.getString("rights_state"),rs.getBoolean("previewable"),rs.getObject("created_at",LocalDateTime.class));
    }

    private static String prefix(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.codePointCount(0, stripped.length()) < 2 ? null : escapeLike(stripped) + "%";
    }

    private static String escapeLike(String value) {
        return value.replace("!","!!").replace("%","!%").replace("_","!_");
    }
}
