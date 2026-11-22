package com.sakezuki.backend.identity;

import org.apache.ibatis.annotations.*;

@Mapper
public interface IdentityMapper {
    record Profile(String termsVersion, String privacyVersion, long consentedAtEpoch, Long verifiedAtEpoch, boolean adult) {}
    record Challenge(String id, Long memberNo, String providerReference, long expiresAtEpoch, boolean consumed) {}
    @Select("SELECT terms_version,privacy_version,consented_at_epoch,verified_at_epoch,adult FROM MEMBER_VERIFICATION WHERE member_no=#{member}")
    Profile profile(Long member);
    @Insert("INSERT INTO MEMBER_VERIFICATION(member_no,terms_version,privacy_version,consented_at_epoch) VALUES(#{member},#{terms},#{privacy},#{now}) " +
            "ON DUPLICATE KEY UPDATE terms_version=#{terms},privacy_version=#{privacy},consented_at_epoch=#{now}")
    void consent(@Param("member") Long member, @Param("terms") String terms, @Param("privacy") String privacy, @Param("now") long now);
    @Insert("INSERT INTO IDENTITY_CHALLENGE(id,member_no,provider_reference,expires_at_epoch) VALUES(#{id},#{memberNo},#{providerReference},#{expiresAtEpoch})")
    void challenge(Challenge challenge);
    @Select("SELECT * FROM IDENTITY_CHALLENGE WHERE id=#{id} AND member_no=#{member}")
    Challenge readChallenge(@Param("member") Long member, @Param("id") String id);
    @Select("SELECT * FROM IDENTITY_CHALLENGE WHERE id=#{id} AND member_no=#{member} FOR UPDATE")
    Challenge lockChallenge(@Param("member") Long member, @Param("id") String id);
    @Insert("INSERT INTO IDENTITY_CLAIM(identity_hash,member_no,created_at_epoch) VALUES(#{hash},#{member},#{now})")
    void claim(@Param("hash") String hash, @Param("member") Long member, @Param("now") long now);
    @Update("UPDATE MEMBER_VERIFICATION SET verified_at_epoch=#{now},adult=TRUE WHERE member_no=#{member}")
    void verified(@Param("member") Long member, @Param("now") long now);
    @Update("UPDATE IDENTITY_CHALLENGE SET consumed=TRUE WHERE id=#{id}")
    void consume(String id);
}
