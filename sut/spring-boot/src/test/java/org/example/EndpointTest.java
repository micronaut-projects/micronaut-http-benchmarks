package org.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({SearchController.class, StatusController.class})
class EndpointTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsTheFirstMatch() throws Exception {
        mockMvc.perform(post("/search/find")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"haystack\":[\"one\",\"needle\"],\"needle\":\"ed\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"listIndex\":1,\"stringIndex\":2}"));
    }

    @Test
    void exposesStatus() throws Exception {
        mockMvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
    }

    @Test
    void doesNotEnableSystemdReadinessWithoutTheServiceProperty(ApplicationContext context) {
        org.junit.jupiter.api.Assertions.assertFalse(context.containsBean("systemdReadinessListener"));
    }
}
