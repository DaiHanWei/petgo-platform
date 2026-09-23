package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.places.dto.PlaceEditForm;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * L0（bug 20260923-540②）：场所标签从逗号文本框改成 6 个同名 checkbox（{@code name="tags"}），
 * 多值提交 → 控制器 {@code @RequestParam String tags}（签名未改）由 Spring 逗号拼接 → {@code PlaceEditForm.of} 的 parseTags 解析。
 * 用与 {@code AdminPlaceController} 同形的参数绑定真跑一遍 MVC 绑定，确认多值不丢、零勾选为空列表。
 */
class PlaceTagsMultiValueBindingTest {

    @RestController
    static class Probe {
        @PostMapping("/probe")
        String probe(@RequestParam(value = "tags", required = false) String tags) {
            PlaceEditForm f = PlaceEditForm.of("Kopi", "CAFE", tags, null, "Jakarta", "Jl. 1", "-6.2", "106.8");
            return String.join("|", f.tags());
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe()).build();

    @Test
    void multipleCheckedTagsAreAllKept() throws Exception {
        mvc.perform(post("/probe").param("tags", "PET_MENU", "OUTDOOR_SEATING", "LARGE_DOG_FRIENDLY"))
                .andExpect(status().isOk())
                .andExpect(content().string("PET_MENU|OUTDOOR_SEATING|LARGE_DOG_FRIENDLY"));
    }

    @Test
    void singleCheckedTag() throws Exception {
        mvc.perform(post("/probe").param("tags", "LEASH_REQUIRED"))
                .andExpect(content().string("LEASH_REQUIRED"));
    }

    @Test
    void noCheckedTagIsEmptyList() throws Exception {
        mvc.perform(post("/probe")).andExpect(status().isOk()).andExpect(content().string(""));
    }

    @Test
    void everyKnownTagRoundTripsThroughTheCheckboxGroup() throws Exception {
        String body = mvc.perform(post("/probe").param("tags", PlaceEditForm.KNOWN_TAGS.toArray(String[]::new)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body.split("\\|")).containsExactlyElementsOf(PlaceEditForm.KNOWN_TAGS);
    }
}
