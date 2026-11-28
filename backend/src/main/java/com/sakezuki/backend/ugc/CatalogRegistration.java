package com.sakezuki.backend.ugc;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogRegistration {
    private final JdbcClient sql;
    private final SubmissionService validation;

    CatalogRegistration(JdbcClient sql, SubmissionService validation) {
        this.sql = sql;
        this.validation = validation;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long publish(SubmissionInput data, String evidence) {
        sql.sql("SELECT id FROM GUIDE_CATALOG_WRITE_MUTEX WHERE id=1 FOR UPDATE").query(Integer.class).single();
        validation.validateReferences(data);
        long brewery = data.brewery().existingNo() != null ? data.brewery().existingNo()
                : createRelation(Entity.BREWERY, null, data.brewery().proposed(), evidence);
        Long brand = data.brand() == null ? null : data.brand().existingNo();
        if (data.brand() != null && data.brand().proposed() != null) {
            brand = createRelation(Entity.BRAND, brewery, data.brand().proposed(), evidence);
        }
        byte[] key = reserveName(Entity.SAKE, brewery, data.sake());
        long sake = insert(sql.sql("""
                INSERT INTO SAKE (brewery_no,brand_no,name_ja,name_ko,name_kana,sake_type,rice,rice_ko,yeast,yeast_ko,
                                  sake_meter_value,acidity,polishing_ratio,alcohol_percentage,capacity_ml,variant,approval_name_key)
                VALUES (:brewery,:brand,:ja,:ko,:kana,:designation,:rice,:riceKo,:yeast,:yeastKo,
                        :meter,:acidity,:polishing,:alcohol,:capacity,:variant,:key)
                """).param("brewery", brewery).param("brand", brand)
                .param("ja", data.sake().ja()).param("ko", data.sake().ko()).param("kana", data.sake().kana())
                .param("designation", data.designation()).param("rice", data.riceJa()).param("riceKo", data.riceKo())
                .param("yeast", data.yeastJa()).param("yeastKo", data.yeastKo())
                .param("meter", display(data.sakeMeter())).param("acidity", display(data.acidity()))
                .param("polishing", display(data.polishingPercent())).param("alcohol", display(data.alcoholPercent()))
                .param("capacity", data.capacityMl()).param("variant", data.variant()).param("key", key));
        source(Entity.SAKE, sake, evidence, data.sourceUrl());
        var profile = sql.sql("""
                INSERT INTO SAKE_RECOMMEND_PROFILE
                    (sake_no,sake_meter_min,sake_meter_max,acidity_min,acidity_max,polishing_ratio_min,polishing_ratio_max)
                VALUES (:sake,:meterMin,:meterMax,:acidityMin,:acidityMax,:polishingMin,:polishingMax)
                """).param("sake", sake);
        bindRange(profile, "meter", data.sakeMeter());
        bindRange(profile, "acidity", data.acidity());
        bindRange(profile, "polishing", data.polishingPercent());
        profile.update();
        return sake;
    }

    private long createRelation(Entity entity, Long brewery, SubmissionInput.Names names, String evidence) {
        byte[] key = reserveName(entity, brewery, names);
        var statement = sql.sql(brewery == null
                        ? "INSERT INTO BREWERY (name_ja,name_ko,name_kana,approval_name_key) VALUES (:ja,:ko,:kana,:key)"
                        : "INSERT INTO BRAND (brewery_no,name_ja,name_ko,name_kana,approval_name_key) VALUES (:brewery,:ja,:ko,:kana,:key)")
                .param("ja", names.ja()).param("ko", names.ko()).param("kana", names.kana()).param("key", key);
        if (brewery != null) statement.param("brewery", brewery);
        long no = insert(statement);
        source(entity, no, evidence, null);
        return no;
    }

    private byte[] reserveName(Entity entity, Long brewery, SubmissionInput.Names names) {
        String ja = CatalogApprovalIdentity.name(names.ja());
        String ko = entity == Entity.SAKE ? null : CatalogApprovalIdentity.name(names.ko());
        String collatedMatch = entity == Entity.SAKE ? "name_ja=:ja" : "(name_ja=:ja OR name_ko=:ko)";
        // The V9 lock serializes this read with catalog imports and other approvals before any public insert.
        var query = sql.sql("SELECT name_ja,name_ko," + collatedMatch + " AS collated_match FROM " + entity.name()
                + (brewery == null ? "" : " WHERE brewery_no=:brewery")).param("ja", names.ja());
        if (brewery != null) query.param("brewery", brewery);
        if (ko != null) query.param("ko", names.ko());
        try (var matches = query.query((rs, row) -> rs.getBoolean("collated_match")
                || ja.equals(CatalogApprovalIdentity.name(rs.getString("name_ja")))
                || (ko != null && ko.equals(CatalogApprovalIdentity.name(rs.getString("name_ko"))))).stream()) {
            if (matches.anyMatch(Boolean::booleanValue)) {
                throw SubmissionErrors.error(409, "기존 카탈로그와 이름이 충돌합니다 (" + entity.name() + "). 기존 관계 또는 상품 구분을 확인해 주세요.");
            }
        }
        return CatalogApprovalIdentity.key(ja);
    }

    private static long insert(JdbcClient.StatementSpec statement) {
        var key = new GeneratedKeyHolder();
        try {
            if (statement.update(key, "no") != 1 || key.getKey() == null) {
                throw new IllegalStateException("catalog row did not return one primary key");
            }
            return key.getKey().longValue();
        } catch (DuplicateKeyException collision) {
            throw SubmissionErrors.error(409, "카탈로그 식별자가 이미 예약되어 있습니다.");
        }
    }

    private void source(Entity entity, long no, String evidence, String url) {
        sql.sql("""
                INSERT INTO %s_SOURCE (%s_no,source_system,canonical_url,evidence_ref,observed_at)
                VALUES (?,'USER_SUBMISSION',?,?,UTC_TIMESTAMP(6))
                """.formatted(entity.name(), entity.name())).params(no,url,evidence).update();
    }

    private static String display(SubmissionInput.Measurement measurement) {
        if (measurement == null) return null;
        String min = measurement.min().toPlainString();
        return measurement.max() == null ? min : min + " ~ " + measurement.max().toPlainString();
    }

    private static void bindRange(JdbcClient.StatementSpec statement, String field, SubmissionInput.Measurement measurement) {
        statement.param(field + "Min", measurement == null ? null : measurement.min())
                .param(field + "Max", measurement == null ? null
                        : measurement.max() == null ? measurement.min() : measurement.max());
    }

    private enum Entity { BREWERY, BRAND, SAKE }
}
