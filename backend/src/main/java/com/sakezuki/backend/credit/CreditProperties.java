package com.sakezuki.backend.credit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties("credits")
public class CreditProperties implements org.springframework.beans.factory.InitializingBean {
    private int initialGrant = 10;
    private Duration historyRetention = Duration.ofDays(30);
    private Duration reservationTimeout = Duration.ofMinutes(10);
    private int dailyGenerationLimit = 100;
    @Override public void afterPropertiesSet() {
        if (initialGrant < 0 || initialGrant > 10000 || dailyGenerationLimit < 0
                || historyRetention == null || historyRetention.isNegative() || historyRetention.isZero()
                || reservationTimeout == null || reservationTimeout.compareTo(Duration.ofMinutes(3)) < 0)
            throw new IllegalArgumentException("크레딧 지급량·보관 기간·예약 만료 설정을 확인해 주세요.");
    }
}
