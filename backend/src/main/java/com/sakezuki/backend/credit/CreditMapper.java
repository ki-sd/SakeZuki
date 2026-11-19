package com.sakezuki.backend.credit;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface CreditMapper {
    record Request(String id, Long memberNo, String idempotencyKey, String payloadHash, String kind,
                   String inputValue, String status, String resultSnapshot, long createdAtEpoch,
                   Long completedAtEpoch, Long expiresAtEpoch) {}
    record History(String id, String kind, String inputValue, long completedAtEpoch, long expiresAtEpoch) {}
    record Entry(long id, String referenceId, String kind, long delta, long balanceAfter, long createdAtEpoch) {}

    @Select("SELECT status FROM MEMBER WHERE no=#{member} FOR UPDATE")
    String lockMember(Long member);
    @Select("SELECT COUNT(*) FROM MEMBER_VERIFICATION WHERE member_no=#{member} AND verified_at_epoch IS NOT NULL AND adult=TRUE")
    boolean verified(Long member);
    @Select("SELECT balance FROM CREDIT_ACCOUNT WHERE member_no=#{member} FOR UPDATE")
    Long lockBalance(Long member);
    @Select("SELECT balance FROM CREDIT_ACCOUNT WHERE member_no=#{member}")
    Long balance(Long member);
    @Insert("INSERT INTO CREDIT_ACCOUNT(member_no,balance) VALUES(#{member},#{amount})")
    void createAccount(@Param("member") Long member, @Param("amount") long amount);
    @Update("UPDATE CREDIT_ACCOUNT SET balance=balance+#{delta} WHERE member_no=#{member} AND balance+#{delta}>=0")
    int change(@Param("member") Long member, @Param("delta") long delta);
    @Select("SELECT * FROM RECOMMEND_REQUEST WHERE member_no=#{member} AND idempotency_key=#{key}")
    Request byKey(@Param("member") Long member, @Param("key") String key);
    @Select("SELECT * FROM RECOMMEND_REQUEST WHERE member_no=#{member} AND id=#{id} FOR UPDATE")
    Request lockRequest(@Param("member") Long member, @Param("id") String id);
    @Select("SELECT * FROM RECOMMEND_REQUEST WHERE member_no=#{member} AND id=#{id}")
    Request byId(@Param("member") Long member, @Param("id") String id);
    @Insert("INSERT INTO RECOMMEND_REQUEST(id,member_no,idempotency_key,payload_hash,kind,input_value,status,created_at_epoch) " +
            "VALUES(#{id},#{memberNo},#{idempotencyKey},#{payloadHash},#{kind},#{inputValue},'RESERVED',#{createdAtEpoch})")
    void reserve(Request request);
    @Update("UPDATE RECOMMEND_REQUEST SET status='COMPLETED',result_snapshot=#{json},completed_at_epoch=#{now},expires_at_epoch=#{expires} WHERE id=#{id} AND status='RESERVED'")
    int complete(@Param("id") String id, @Param("json") String json, @Param("now") long now, @Param("expires") long expires);
    @Update("UPDATE RECOMMEND_REQUEST SET status=#{status},input_value=NULL WHERE id=#{id} AND status='RESERVED'")
    int fail(@Param("id") String id, @Param("status") String status);
    @Insert("INSERT INTO CREDIT_LEDGER(member_no,reference_id,kind,delta,balance_after,created_at_epoch) " +
            "VALUES(#{member},#{reference},#{kind},#{delta},#{balance},#{now})")
    void ledger(@Param("member") Long member, @Param("reference") String reference, @Param("kind") String kind,
                @Param("delta") long delta, @Param("balance") long balance, @Param("now") long now);
    @Select("SELECT id,kind,input_value,completed_at_epoch,expires_at_epoch FROM RECOMMEND_REQUEST " +
            "WHERE member_no=#{member} AND status='COMPLETED' AND expires_at_epoch>#{now} " +
            "ORDER BY completed_at_epoch DESC,id DESC LIMIT 30 OFFSET #{offset}")
    List<History> history(@Param("member") Long member, @Param("now") long now, @Param("offset") int offset);
    @Select("SELECT id,reference_id,kind,delta,balance_after,created_at_epoch FROM CREDIT_LEDGER WHERE member_no=#{member} ORDER BY id DESC LIMIT 50 OFFSET #{offset}")
    List<Entry> entries(@Param("member") Long member, @Param("offset") int offset);
    @Select("SELECT * FROM RECOMMEND_REQUEST WHERE status='RESERVED' AND created_at_epoch<#{before} ORDER BY created_at_epoch LIMIT 100")
    List<Request> stale(long before);
    @Update("UPDATE RECOMMEND_REQUEST SET result_snapshot=NULL,input_value=NULL WHERE status='COMPLETED' AND expires_at_epoch<=#{now} AND result_snapshot IS NOT NULL LIMIT 100")
    int eraseExpired(long now);
    @Delete("DELETE FROM IDENTITY_CHALLENGE WHERE expires_at_epoch<#{now} LIMIT 100")
    int eraseChallenges(long now);
}
