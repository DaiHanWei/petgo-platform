package com.tailtopia.place.service;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本版唯一实现：一律返回配置项 {@code petgo.places.default-city}（application.yml，当前 Jakarta）。
 * 坐标参数本版不用（见 {@link PlaceCityResolver} 的预留说明）。
 */
@Component
public class DefaultPlaceCityResolver implements PlaceCityResolver {

    private final String defaultCity;

    public DefaultPlaceCityResolver(@Value("${petgo.places.default-city}") String defaultCity) {
        if (defaultCity == null || defaultCity.isBlank() || defaultCity.strip().length() > 60) {
            throw new IllegalStateException("petgo.places.default-city 必须是 1～60 字的城市名");
        }
        this.defaultCity = defaultCity.strip();
    }

    @Override
    public String resolve(BigDecimal lat, BigDecimal lng) {
        return defaultCity;
    }

    @Override
    public String defaultCity() {
        return defaultCity;
    }
}
