package com.sakezuki.backend.auth.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface RefreshTokenMapper {
    record Family(String id, Long memberNo, long expiresAt, boolean revoked) {}
    record Token(String tokenHash, String familyId, boolean used) {}

    @Insert("INSERT INTO AUTH_TOKEN_FAMILY(id, member_no, expires_at_epoch) VALUES(#{id}, #{memberNo}, #{expiresAt})")
    void insertFamily(@Param("id") String id, @Param("memberNo") Long memberNo, @Param("expiresAt") long expiresAt);

    @Insert("INSERT INTO AUTH_REFRESH_TOKEN(token_hash, family_id) VALUES(#{hash}, #{familyId})")
    void insertToken(@Param("hash") String hash, @Param("familyId") String familyId);

    @Select("SELECT token_hash, family_id, used FROM AUTH_REFRESH_TOKEN WHERE token_hash=#{hash}")
    Token findToken(String hash);

    @Select("SELECT token_hash, family_id, used FROM AUTH_REFRESH_TOKEN WHERE token_hash=#{hash} FOR UPDATE")
    Token lockToken(String hash);

    @Select("SELECT id, member_no, expires_at_epoch AS expires_at, revoked FROM AUTH_TOKEN_FAMILY WHERE id=#{id} FOR UPDATE")
    Family lockFamily(String id);

    @Update("UPDATE AUTH_REFRESH_TOKEN SET used=TRUE WHERE token_hash=#{hash}")
    void useToken(String hash);

    @Update("UPDATE AUTH_TOKEN_FAMILY SET revoked=TRUE WHERE id=#{id}")
    void revokeFamily(String id);

    @Delete("DELETE FROM AUTH_TOKEN_FAMILY WHERE expires_at_epoch < UNIX_TIMESTAMP()")
    void deleteExpired();
}
