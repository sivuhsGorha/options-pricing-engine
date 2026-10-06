package com.sbk.optionspricer.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** JSON output for the API. Values are serialized by Jackson, so text is always escaped correctly. */
final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    /** Rounds for display; non-finite values become 0 so a body never contains NaN or Infinity. */
    static double round(double value, int places) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }
}
