package com.tailtopia.tailsonality.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标（V1.3.2 Story 2.5 · AC1.5）：主人类型 JSON 形状 + 写入校验。
 *
 * <p>App 侧 {@code test/tailsonality/owner_type_wire_contract_test.dart} 与本文件同集。
 */
class TailsonalityOwnerTypeResponseContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void setTypeHasExactlyTypeCode() {
        assertThat(wire(new OwnerTypeResponse("INFP"))).isEqualTo(Map.of("typeCode", "INFP"));
    }

    @Test
    void unsetIsEmptyObject() {
        assertThat(wire(new OwnerTypeResponse(null))).isEmpty();
    }

    @Test
    void requestAcceptsOnly16UppercaseCodes() {
        for (String ok : new String[] {"ENTJ", "INFP", "ISTP", "ESFJ"}) {
            assertThat(VALIDATOR.validate(new OwnerTypeRequest(ok))).as(ok).isEmpty();
        }
        for (String bad : new String[] {"INFP-H", "infp", "Infp", "ENT", "ENTJX", "XNTJ", "EXTJ", "ENXJ", "ENTX", ""}) {
            assertThat(VALIDATOR.validate(new OwnerTypeRequest(bad))).as(bad).isNotEmpty();
        }
        assertThat(VALIDATOR.validate(new OwnerTypeRequest(null))).isNotEmpty();
        assertThat(Set.of(OwnerTypeRequest.class.getRecordComponents()).size()).isEqualTo(1);
    }
}
